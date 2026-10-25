package com.loopers.experiment;

import com.loopers.application.product.facade.ProductFacade;
import com.loopers.domain.product.cache.ProductDetailCache;
import com.loopers.domain.product.cache.ProductListCache;
import com.loopers.domain.product.enums.ProductSort;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 인기 키의 캐시가 사라진 직후 동시 요청이 몰릴 때(스템피드) 원본 DB 조회가 몇 번 일어나는지 잰다 (4주차 docs/week4/detail-cache-design.md 8).
 * 제품 코드가 아니며 결과는 대책을 정하는 근거로 씀. 지금 캐시에는 동시 miss 대책이 없다.
 *
 * <ul>
 *   <li>한 시행: 그 키를 무효화(버전 올림)한 뒤, N 개 스레드가 출발 신호를 함께 받아 같은 요청을 한 번씩 보낸다. 가장 몰리는 경우다</li>
 *   <li>원본 조회 수는 Hibernate 가 준비한 SQL 수를 원본 한 번의 SQL 수(상세 1, 목록 2)로 나눠 센다</li>
 *   <li>요청 지연은 출발 신호부터 그 요청이 끝날 때까지다. 인기 상품 상세와 전체 목록 좋아요순(원본 SQL 2개)을 함께 잰다</li>
 *   <li>Facade 를 직접 부른다(MockMvc · 톰캣 제외). 테스트 커넥션 풀은 10개다. 원본을 읽는 요청은 커넥션을 기다릴 수 있다</li>
 * </ul>
 * 실행: RUN_EXPERIMENT=true ./gradlew :apps:commerce-api:test --tests '*CacheStampedeExperiment'
 */
@SpringBootTest(properties = {
    "spring.jpa.show-sql=false",
    "spring.jpa.properties.hibernate.generate_statistics=true"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "RUN_EXPERIMENT", matches = "true")
class CacheStampedeExperiment {

    private static final String SEED = "seed/week4-product-seed.sql";
    private static final Path REPORT = Path.of("build", "experiment", "cache-stampede.md");
    /** seed 의 인기 상품(좋아요 250) */
    private static final long HOT_PRODUCT_ID = 97L;
    private static final List<Integer> CONCURRENCY = List.of(1, 10, 50, 100);
    private static final int WARMUP_TRIALS = 3;
    private static final int MEASURED_TRIALS = 20;
    private static final long WAIT_SECONDS = 30;

    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.0")).withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("datasource.redis.master.host", REDIS::getHost);
        registry.add("datasource.redis.master.port", () -> REDIS.getMappedPort(6379));
        registry.add("datasource.redis.replicas[0].host", REDIS::getHost);
        registry.add("datasource.redis.replicas[0].port", () -> REDIS.getMappedPort(6379));
    }

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }

    /** 장면: 이름, 원본 한 번의 SQL 수, 키 버리기, 요청 */
    private record Scene(String name, int statementsPerLoad, Runnable invalidate, Action request) {}

    /** 한 시행의 결과 */
    private record Trial(long loads, long[] nanos, int errors) {}

    @Autowired
    private ProductFacade productFacade;

    @Autowired
    private ProductDetailCache productDetailCache;

    @Autowired
    private ProductListCache productListCache;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private RedisCleanUp redisCleanUp;

    @BeforeAll
    void setUp() throws Exception {
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(SEED));
        }
    }

    @AfterAll
    void cleanUp() {
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    @Test
    void measureStampede() throws Exception {
        List<Scene> scenes = List.of(
            new Scene("상세 · 상품 " + HOT_PRODUCT_ID, 1,
                () -> productDetailCache.invalidate(HOT_PRODUCT_ID),
                () -> productFacade.getProduct(HOT_PRODUCT_ID)),
            new Scene("목록 · 전체 · likes_desc", 2,
                () -> productListCache.invalidate(null),
                () -> productFacade.getProducts(null, ProductSort.LIKES_DESC, PageRequest.of(0, 20)))
        );

        List<String> lines = new ArrayList<>();
        lines.add("# 캐시 스템피드 측정");
        lines.add("");
        lines.add("- 한 시행: 키 무효화 → N 개 스레드가 출발 신호를 함께 받아 같은 요청을 한 번씩. 준비 " + WARMUP_TRIALS + "회 뒤 " + MEASURED_TRIALS + "회");
        lines.add("- 데이터: " + SEED + " (상품 1만 개). Facade 직접 호출, 테스트 커넥션 풀 10, Redis " + REDIS.getDockerImageName() + " 단일 노드");
        lines.add("");
        lines.add("| 장면 | 동시 요청 N | 원본 조회 수 (최소 / 평균 / 최대) | 요청 지연 p50(ms) | p95(ms) | 최대(ms) | 실패 |");
        lines.add("|---|---|---|---|---|---|---|");

        for (Scene scene : scenes) {
            assertHitAfterMiss(scene);
            for (int concurrency : CONCURRENCY) {
                ExecutorService executor = Executors.newFixedThreadPool(concurrency);
                try {
                    for (int i = 0; i < WARMUP_TRIALS; i++) {
                        trial(scene, concurrency, executor);
                    }
                    List<Trial> trials = new ArrayList<>();
                    for (int i = 0; i < MEASURED_TRIALS; i++) {
                        trials.add(trial(scene, concurrency, executor));
                    }
                    lines.add(row(scene, concurrency, trials));
                } finally {
                    executor.shutdownNow();
                    executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS);
                }
            }
        }

        lines.add("");
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, String.join("\n", lines));
    }

    /** 장면이 의도대로인지: 무효화 뒤 첫 요청은 원본 한 번, 다음 요청은 SQL 없음 */
    private void assertHitAfterMiss(Scene scene) throws Exception {
        scene.invalidate().run();
        statistics().clear();
        scene.request().run();
        long miss = statistics().getPrepareStatementCount();
        statistics().clear();
        scene.request().run();
        long hit = statistics().getPrepareStatementCount();
        assertThat(miss).as(scene.name() + " miss").isEqualTo(scene.statementsPerLoad());
        assertThat(hit).as(scene.name() + " hit").isZero();
    }

    private Trial trial(Scene scene, int concurrency, ExecutorService executor) throws Exception {
        scene.invalidate().run();
        statistics().clear();
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        List<Future<Long>> futures = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                long begin = System.nanoTime();
                try {
                    scene.request().run();
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
                return System.nanoTime() - begin;
            }));
        }
        assertThat(ready.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        long[] nanos = new long[concurrency];
        for (int i = 0; i < concurrency; i++) {
            nanos[i] = futures.get(i).get(WAIT_SECONDS, TimeUnit.SECONDS);
        }
        long loads = statistics().getPrepareStatementCount() / scene.statementsPerLoad();
        return new Trial(loads, nanos, errors.get());
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private static String row(Scene scene, int concurrency, List<Trial> trials) {
        long[] loads = trials.stream().mapToLong(Trial::loads).toArray();
        long[] nanos = trials.stream().flatMapToLong(trial -> Arrays.stream(trial.nanos())).sorted().toArray();
        int errors = trials.stream().mapToInt(Trial::errors).sum();
        return String.format("| %s | %d | %d / %.1f / %d | %.2f | %.2f | %.2f | %d |",
            scene.name(), concurrency,
            Arrays.stream(loads).min().orElse(0), Arrays.stream(loads).average().orElse(0), Arrays.stream(loads).max().orElse(0),
            percentile(nanos, 0.50), percentile(nanos, 0.95), nanos[nanos.length - 1] / 1e6, errors);
    }

    private static double percentile(long[] sorted, double p) {
        int index = (int) Math.ceil(p * sorted.length) - 1;
        return sorted[Math.max(index, 0)] / 1e6;
    }
}
