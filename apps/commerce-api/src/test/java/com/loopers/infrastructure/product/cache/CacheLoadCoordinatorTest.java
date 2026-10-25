package com.loopers.infrastructure.product.cache;

import com.loopers.config.ProductCacheRedisConfig;
import com.loopers.support.redis.ProductCacheConnection;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 서로 다른 CacheLoadCoordinator 둘이 같은 Redis 를 써서 서버 두 대를 흉내 냄 (4주차 stampede-design 6) */
@SpringBootTest
class CacheLoadCoordinatorTest {

    private static final String KEY = "test:data:ver=1";
    private static final long WAIT_SECONDS = 10;

    @Autowired
    @Qualifier(ProductCacheRedisConfig.MASTER_TEMPLATE)
    private StringRedisTemplate redis;

    @Autowired
    private ProductCacheConnection productCacheConnection;

    @Autowired
    private RedisCleanUp redisCleanUp;

    @BeforeEach
    void connectCache() {
        productCacheConnection.connect();
    }

    private final ExecutorService executor = Executors.newCachedThreadPool();

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS);
        redisCleanUp.truncateAll();
    }

    private CacheLoadCoordinator coordinator(long lockTtlMillis, long waitLimitMillis) {
        return new CacheLoadCoordinator(redis, lockTtlMillis, waitLimitMillis, 10);
    }

    private CacheLoadCoordinator coordinator() {
        return coordinator(3_000, 3_000);
    }

    private Supplier<Optional<String>> readStored(String key) {
        return () -> Optional.ofNullable(redis.opsForValue().get(key));
    }

    private Consumer<String> store(String key) {
        return value -> redis.opsForValue().set(key, value);
    }

    /** 부를 때마다 수를 세고, 정한 시간만큼 걸림 */
    private static Supplier<String> slowLoad(AtomicInteger calls, long millis) {
        return () -> {
            calls.incrementAndGet();
            sleep(millis);
            return "원본";
        };
    }

    private List<Future<String>> startTogether(int count, Callable<String> task) throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                return task.call();
            }));
        }
        assertThat(ready.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        return futures;
    }

    private static List<String> results(List<Future<String>> futures) throws Exception {
        List<String> results = new ArrayList<>();
        for (Future<String> future : futures) {
            results.add(future.get(WAIT_SECONDS, TimeUnit.SECONDS));
        }
        return results;
    }

    @DisplayName("한 서버에서 같은 키로 동시에 N 개가 와도, 원본은 한 번 읽고 모두 같은 값을 받는다.")
    @Test
    void loadsOnce_withinInstance() throws Exception {
        // arrange
        CacheLoadCoordinator coordinator = coordinator();
        AtomicInteger calls = new AtomicInteger();

        // act
        List<Future<String>> futures = startTogether(20,
            () -> coordinator.load(KEY, readStored(KEY), slowLoad(calls, 100), store(KEY)));

        // assert
        assertAll(
            () -> assertThat(results(futures)).hasSize(20).containsOnly("원본"),
            () -> assertThat(calls.get()).isEqualTo(1),
            () -> assertThat(redis.opsForValue().get(KEY)).isEqualTo("원본")
        );
    }

    @DisplayName("서버 두 대가 같은 키로 동시에 와도, 원본은 한 번 읽는다. 락을 못 잡은 서버는 저장된 값을 읽는다.")
    @Test
    void loadsOnce_acrossInstances() throws Exception {
        // arrange
        CacheLoadCoordinator first = coordinator();
        CacheLoadCoordinator second = coordinator();
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger turn = new AtomicInteger();

        // act
        List<Future<String>> futures = startTogether(20, () -> {
            CacheLoadCoordinator coordinator = turn.getAndIncrement() % 2 == 0 ? first : second;
            return coordinator.load(KEY, readStored(KEY), slowLoad(calls, 100), store(KEY));
        });

        // assert
        assertAll(
            () -> assertThat(results(futures)).hasSize(20).containsOnly("원본"),
            () -> assertThat(calls.get()).isEqualTo(1)
        );
    }

    @DisplayName("앱 안 첫 요청의 원본이 실패하면, 첫 요청은 그 오류를 받고 기다리던 요청은 각자 원본을 읽되 저장하지 않는다.")
    @Test
    void waitersLoadThemselves_withoutStoring_whenFirstLoadFails() throws Exception {
        // arrange
        CacheLoadCoordinator coordinator = coordinator();
        CountDownLatch firstLoadStarted = new CountDownLatch(1);
        CountDownLatch failFirstLoad = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException("원본 실패");
        Supplier<String> load = () -> {
            if (calls.incrementAndGet() == 1) {
                firstLoadStarted.countDown();
                await(failFirstLoad);
                throw failure;
            }
            return "원본";
        };
        Future<String> first = executor.submit(() -> coordinator.load(KEY, readStored(KEY), load, store(KEY)));
        assertThat(firstLoadStarted.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        List<Future<String>> waiters = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            waiters.add(executor.submit(() -> coordinator.load(KEY, readStored(KEY), load, store(KEY))));
        }
        sleep(100);

        // act
        failFirstLoad.countDown();

        // assert
        ExecutionException result = assertThrows(ExecutionException.class, () -> first.get(WAIT_SECONDS, TimeUnit.SECONDS));
        assertAll(
            () -> assertThat(result.getCause()).isSameAs(failure),
            () -> assertThat(results(waiters)).containsOnly("원본"),
            () -> assertThat(calls.get()).isEqualTo(4),
            () -> assertThat(redis.opsForValue().get(KEY)).isNull(),
            () -> assertThat(redis.opsForValue().get(CacheLoadCoordinator.lockKey(KEY))).isNull()
        );
    }

    @DisplayName("다른 서버가 락만 쥐고 값을 만들지 않으면, 대기 한도 뒤 각자 원본을 읽고 저장한다.")
    @Test
    void loadsAfterWaitLimit_whenLockHolderNeverStores() {
        // arrange
        CacheLoadCoordinator coordinator = coordinator(3_000, 200);
        redis.opsForValue().set(CacheLoadCoordinator.lockKey(KEY), "죽은 서버의 토큰");
        AtomicInteger calls = new AtomicInteger();

        // act
        long start = System.nanoTime();
        String result = coordinator.load(KEY, readStored(KEY), slowLoad(calls, 0), store(KEY));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        // assert
        assertAll(
            () -> assertThat(result).isEqualTo("원본"),
            () -> assertThat(calls.get()).isEqualTo(1),
            () -> assertThat(elapsedMillis).isGreaterThanOrEqualTo(200),
            () -> assertThat(redis.opsForValue().get(KEY)).isEqualTo("원본"),
            () -> assertThat(redis.opsForValue().get(CacheLoadCoordinator.lockKey(KEY))).isEqualTo("죽은 서버의 토큰")
        );
    }

    @DisplayName("다른 서버가 락을 쥐고 있다가 값을 저장하면, 원본을 읽지 않고 그 값을 쓴다.")
    @Test
    void usesStoredValue_whenOtherInstanceStores() throws Exception {
        // arrange
        CacheLoadCoordinator coordinator = coordinator();
        redis.opsForValue().set(CacheLoadCoordinator.lockKey(KEY), "다른 서버의 토큰");
        AtomicInteger calls = new AtomicInteger();
        Future<String> waiting = executor.submit(() -> coordinator.load(KEY, readStored(KEY), slowLoad(calls, 0), store(KEY)));
        sleep(50);

        // act
        redis.opsForValue().set(KEY, "다른 서버가 저장한 값");

        // assert
        assertAll(
            () -> assertThat(waiting.get(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo("다른 서버가 저장한 값"),
            () -> assertThat(calls.get()).isZero()
        );
    }

    @DisplayName("버전이 다른 데이터 키는, 서로 기다리지 않는다.")
    @Test
    void doesNotWait_forOtherVersion() throws Exception {
        // arrange
        CacheLoadCoordinator coordinator = coordinator();
        CountDownLatch oldLoadStarted = new CountDownLatch(1);
        CountDownLatch releaseOldLoad = new CountDownLatch(1);
        Future<String> oldVersion = executor.submit(() -> coordinator.load("test:data:ver=1", readStored("test:data:ver=1"), () -> {
            oldLoadStarted.countDown();
            await(releaseOldLoad);
            return "옛 버전";
        }, store("test:data:ver=1")));
        assertThat(oldLoadStarted.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

        // act
        String newVersion = coordinator.load("test:data:ver=2", readStored("test:data:ver=2"), () -> "새 버전", store("test:data:ver=2"));

        // assert
        assertAll(
            () -> assertThat(newVersion).isEqualTo("새 버전"),
            () -> assertThat(oldVersion.isDone()).isFalse()
        );
        releaseOldLoad.countDown();
        assertThat(oldVersion.get(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo("옛 버전");
    }

    @DisplayName("락이 만료된 뒤 다른 서버가 새로 잡았으면, 원본을 다 읽은 뒤에도 그 락을 지우지 않는다.")
    @Test
    void releasesOnlyOwnLock() {
        // arrange
        CacheLoadCoordinator coordinator = coordinator(100, 3_000);
        String lockKey = CacheLoadCoordinator.lockKey(KEY);
        Supplier<String> load = () -> {
            sleep(200);
            redis.opsForValue().set(lockKey, "새로 잡은 서버의 토큰");
            return "원본";
        };

        // act
        coordinator.load(KEY, readStored(KEY), load, store(KEY));

        // assert
        assertThat(redis.opsForValue().get(lockKey)).isEqualTo("새로 잡은 서버의 토큰");
    }

    @DisplayName("락을 잡다 Redis 오류가 나면, 락 없이 원본을 읽는다.")
    @Test
    void loadsWithoutLock_whenRedisFails() {
        // arrange
        LettuceConnectionFactory unreachable = new LettuceConnectionFactory(new RedisStandaloneConfiguration("localhost", 1),
            LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(100)).build());
        unreachable.afterPropertiesSet();
        try {
            CacheLoadCoordinator coordinator = new CacheLoadCoordinator(new StringRedisTemplate(unreachable), 3_000, 3_000, 10);
            AtomicInteger calls = new AtomicInteger();
            AtomicInteger stores = new AtomicInteger();

            // act
            String result = coordinator.load(KEY, Optional::empty, slowLoad(calls, 0), value -> stores.incrementAndGet());

            // assert
            assertAll(
                () -> assertThat(result).isEqualTo("원본"),
                () -> assertThat(calls.get()).isEqualTo(1),
                () -> assertThat(stores.get()).isEqualTo(1)
            );
        } finally {
            unreachable.destroy();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("신호를 받지 못함");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
