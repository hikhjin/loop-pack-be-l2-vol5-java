package com.loopers.infrastructure.product;

import com.loopers.config.ProductCacheRedisConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 캐시 miss 뒤 원본 조회를 데이터 키마다 한 번으로 모은다 (4주차 docs/week4/stampede-design.md). 목록 · 상세 캐시가 함께 쓴다.
 * <ol>
 *   <li>앱 안: 같은 데이터 키를 이미 읽고 있는 요청이 있으면 그 결과를 기다린다</li>
 *   <li>앱 안의 첫 요청만 Redis 락을 잡아 원본을 읽고 저장한다. 못 잡으면 다른 서버가 저장하기를 기다린다</li>
 * </ol>
 * 기다리던 첫 요청이 실패하거나 대기 한도를 넘으면 각자 원본을 읽는다. 캐시 쪽 문제로 조회가 실패하지 않게 하기 위함이다 (1, 3).
 * 데이터 키에 버전이 들어 있으므로 락도 버전마다 따로다
 */
@Slf4j
@Component
public class CacheLoadCoordinator {

    /** 내 토큰일 때만 지운다. 락 만료 뒤 다른 서버가 새로 잡은 락을 지우지 않기 위함 (3) */
    private static final RedisScript<Long> RELEASE = new DefaultRedisScript<>(
        "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end return 0",
        Long.class);

    private final StringRedisTemplate master;
    private final Duration lockTtl;
    private final Duration waitLimit;
    private final Duration pollInterval;

    /** 앱 안에서 지금 원본을 읽고 있는 데이터 키와 그 결과 */
    private final ConcurrentHashMap<String, CompletableFuture<Object>> inFlight = new ConcurrentHashMap<>();

    public CacheLoadCoordinator(
        @Qualifier(ProductCacheRedisConfig.MASTER_TEMPLATE) StringRedisTemplate master,
        @Value("${product-cache.load-lock.ttl-ms:3000}") long lockTtlMillis,
        @Value("${product-cache.load-lock.wait-ms:3000}") long waitLimitMillis,
        @Value("${product-cache.load-lock.poll-ms:10}") long pollIntervalMillis
    ) {
        this.master = master;
        this.lockTtl = Duration.ofMillis(lockTtlMillis);
        this.waitLimit = Duration.ofMillis(waitLimitMillis);
        this.pollInterval = Duration.ofMillis(pollIntervalMillis);
    }

    /**
     * 데이터 키의 값을 원본에서 한 번만 읽어 저장하고 돌려준다. 원본(load)의 오류는 감싸지 않고 그대로 나간다.
     *
     * @param dataKey    버전이 들어간 데이터 키. 앱 안 합치기와 락의 단위다
     * @param readStored 저장된 값을 읽는다. 없으면 비어 있음. 다른 서버의 저장을 기다릴 때 쓴다
     * @param load       원본을 읽는다
     * @param store      원본 결과를 데이터 키에 저장한다. 저장 실패는 store 가 삼켜야 한다
     */
    @SuppressWarnings("unchecked")
    public <T> T load(String dataKey, Supplier<Optional<T>> readStored, Supplier<T> load, Consumer<T> store) {
        CompletableFuture<Object> mine = new CompletableFuture<>();
        CompletableFuture<Object> running = inFlight.putIfAbsent(dataKey, mine);
        if (running != null) {
            return (T) awaitInFlight(running, load);
        }
        try {
            T value = loadOnceAcrossInstances(dataKey, readStored, load, store);
            mine.complete(value);
            return value;
        } catch (RuntimeException | Error e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(dataKey, mine);
        }
    }

    /** 앱 안 첫 요청의 결과를 기다린다. 실패했거나 대기 한도를 넘으면 각자 원본을 읽고 저장하지 않는다 (3) */
    private Object awaitInFlight(CompletableFuture<Object> running, Supplier<?> load) {
        try {
            return running.get(waitLimit.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException | TimeoutException e) {
            return load.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return load.get();
        }
    }

    private <T> T loadOnceAcrossInstances(String dataKey, Supplier<Optional<T>> readStored, Supplier<T> load, Consumer<T> store) {
        String lockKey = lockKey(dataKey);
        String token = UUID.randomUUID().toString();
        boolean locked;
        try {
            locked = Boolean.TRUE.equals(master.opsForValue().setIfAbsent(lockKey, token, lockTtl));
        } catch (RuntimeException e) {
            log.warn("원본 조회 락을 잡지 못해 락 없이 원본을 읽음. key={}", lockKey, e);
            return loadAndStore(load, store);
        }
        if (locked) {
            try {
                return loadAndStore(load, store);
            } finally {
                release(lockKey, token);
            }
        }
        return awaitOtherInstance(readStored).orElseGet(() -> loadAndStore(load, store));
    }

    /** 다른 서버가 저장하기를 확인 간격마다 기다린다. 대기 한도를 넘거나 읽기에 실패하면 비어 있음 */
    private <T> Optional<T> awaitOtherInstance(Supplier<Optional<T>> readStored) {
        long deadline = System.nanoTime() + waitLimit.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
            try {
                Optional<T> stored = readStored.get();
                if (stored.isPresent()) {
                    return stored;
                }
            } catch (RuntimeException e) {
                log.warn("다른 서버의 저장을 기다리다 캐시를 읽지 못해 원본을 읽음", e);
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static <T> T loadAndStore(Supplier<T> load, Consumer<T> store) {
        T value = load.get();
        store.accept(value);
        return value;
    }

    // 해제 실패는 락 만료가 정리한다
    private void release(String lockKey, String token) {
        try {
            master.execute(RELEASE, List.of(lockKey), token);
        } catch (RuntimeException e) {
            log.warn("원본 조회 락을 풀지 못함. 만료로 정리됨. key={}", lockKey, e);
        }
    }

    static String lockKey(String dataKey) {
        return "lock:" + dataKey;
    }
}
