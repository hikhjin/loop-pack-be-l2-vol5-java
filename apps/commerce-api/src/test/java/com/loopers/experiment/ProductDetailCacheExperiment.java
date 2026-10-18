package com.loopers.experiment;

import com.loopers.application.brand.BrandFacade;
import com.loopers.application.product.AdminProductFacade;
import com.loopers.domain.product.ProductDetailCache;
import com.loopers.domain.product.ProductView;
import com.loopers.infrastructure.product.RedisProductDetailCache;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 상품 상세 캐시의 hit 지연을 MockMvc 기준으로 DB 만 · 캐시 hit 로 비교한다 (4주차 docs/week4/detail-cache-design.md 8).
 * 제품 코드가 아니며 결과는 설계 문서의 근거로 씀.
 *
 * <ul>
 *   <li>포트(ProductDetailCache)를 바꿔 끼울 수 있는 구현을 @Primary 로 두고, 같은 컨텍스트에서 방식만 바꿔 같은 요청을 보낸다</li>
 *   <li>캐시는 제품 코드(RedisProductDetailCache, 버전 · 데이터를 스크립트 한 번으로 읽음)다.
 *       전환 전 C(왕복 2회)와의 비교 결과는 docs/week4/product-detail-cache.md 에 남아 있다</li>
 *   <li>회차마다 두 방식을 한 번씩, 순서를 돌려 가며 잰다. 측정 전에 두 방식의 응답이 같고 hit 에 SQL 이 없는지 확인한다</li>
 *   <li>이 실험 전용 Redis 단일 노드 컨테이너를 쓴다. master 와 replica 우선 연결이 같은 노드를 가리킨다</li>
 * </ul>
 * 실행: RUN_EXPERIMENT=true ./gradlew :apps:commerce-api:test --tests '*ProductDetailCacheExperiment'
 */
@SpringBootTest(properties = {
    "spring.jpa.show-sql=false",
    "spring.jpa.properties.hibernate.generate_statistics=true"
})
@AutoConfigureMockMvc
@Import(ProductDetailCacheExperiment.SwitchConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "RUN_EXPERIMENT", matches = "true")
class ProductDetailCacheExperiment {

    private static final Path REPORT = Path.of("build", "experiment", "product-detail-cache.md");
    private static final int WARMUP_ROUNDS = 500;
    private static final int MEASURED_ROUNDS = 3_000;

    private static final String LATENCY_HEADER = "| 방식 | Redis 왕복 | 요청당 SQL | 표본 | 평균(ms) | p50(ms) | p95(ms) | p99(ms) | 최대(ms) |\n"
        + "|---|---|---|---|---|---|---|---|---|";

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

    enum Mode { DB_ONLY, CACHE }

    /** 실험 중 방식을 바꿔 끼우는 포트 구현. 무효화는 늘 제품 코드로 한다 */
    static final class SwitchableProductDetailCache implements ProductDetailCache {
        private final RedisProductDetailCache cache;
        private volatile Mode mode = Mode.CACHE;

        SwitchableProductDetailCache(RedisProductDetailCache cache) {
            this.cache = cache;
        }

        void use(Mode mode) {
            this.mode = mode;
        }

        @Override
        public Optional<ProductView> get(Long productId, Supplier<Optional<ProductView>> loader) {
            return switch (mode) {
                case DB_ONLY -> loader.get();
                case CACHE -> cache.get(productId, loader);
            };
        }

        @Override
        public void invalidate(Long productId) {
            cache.invalidate(productId);
        }
    }

    @TestConfiguration
    static class SwitchConfig {
        @Bean
        @Primary
        SwitchableProductDetailCache switchableProductDetailCache(RedisProductDetailCache cache) {
            return new SwitchableProductDetailCache(cache);
        }
    }

    private record Scene(Mode mode, String roundTrips) {}

    private static final List<Scene> SCENES = List.of(
        new Scene(Mode.DB_ONLY, "0"),
        new Scene(Mode.CACHE, "1")
    );

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SwitchableProductDetailCache cache;

    @Autowired
    private BrandFacade brandFacade;

    @Autowired
    private AdminProductFacade adminProductFacade;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private RedisCleanUp redisCleanUp;

    private Long productId;

    @BeforeAll
    void setUp() {
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
        Long brandId = brandFacade.createBrand("brand-98", "brand-98 description").id();
        productId = adminProductFacade.createProduct(brandId, "product-97", 3_689L).id();
        adminProductFacade.changeStock(productId, 13);
    }

    @AfterAll
    void cleanUp() {
        cache.use(Mode.CACHE);
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    @Test
    void measureDetailHit() throws Exception {
        // 확인: 두 방식의 응답이 같고, 캐시를 채운 뒤 hit 는 SQL 이 없다
        long[] statements = new long[SCENES.size()];
        String expected = null;
        for (int i = 0; i < SCENES.size(); i++) {
            cache.use(SCENES.get(i).mode());
            request();
            statistics().clear();
            String body = request();
            statements[i] = statistics().getPrepareStatementCount();
            if (expected == null) {
                expected = body;
            }
            assertThat(body).as(SCENES.get(i).mode().name()).isEqualTo(expected);
        }
        assertThat(statements).containsExactly(1, 0);

        long[][] nanos = new long[SCENES.size()][MEASURED_ROUNDS];
        for (int round = 0; round < WARMUP_ROUNDS + MEASURED_ROUNDS; round++) {
            for (int offset = 0; offset < SCENES.size(); offset++) {
                int index = (round + offset) % SCENES.size();
                cache.use(SCENES.get(index).mode());
                long start = System.nanoTime();
                request();
                long elapsed = System.nanoTime() - start;
                if (round >= WARMUP_ROUNDS) {
                    nanos[index][round - WARMUP_ROUNDS] = elapsed;
                }
            }
        }

        List<String> lines = new ArrayList<>();
        lines.add("# 상품 상세 hit 측정 (MockMvc)");
        lines.add("");
        lines.add("- 요청: `GET /api/v1/products/" + productId + "`. MockMvc 로 한 스레드에서 차례로 요청. 네트워크 · 톰캣은 포함하지 않음");
        lines.add("- Redis " + REDIS.getDockerImageName() + " 단일 노드 (Testcontainers). MySQL Testcontainers, 테스트 커넥션 풀 10");
        lines.add("- 응답 본문 " + expected.getBytes(StandardCharsets.UTF_8).length + " bytes. 두 방식의 응답이 같음을 확인함");
        lines.add("- 회차마다 두 방식을 한 번씩, 순서를 돌려 가며 잼. 준비 " + WARMUP_ROUNDS + "회차 뒤 " + MEASURED_ROUNDS + "회차");
        lines.add("");
        lines.add(LATENCY_HEADER);
        for (int i = 0; i < SCENES.size(); i++) {
            lines.add(latencyRow(SCENES.get(i), statements[i], nanos[i]));
        }
        lines.add("");
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, String.join("\n", lines));
    }

    private String request() throws Exception {
        return mvc.perform(get("/api/v1/products/" + productId))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private static String latencyRow(Scene scene, long statements, long[] nanos) throws IOException {
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        double mean = Arrays.stream(sorted).average().orElse(0);
        String name = switch (scene.mode()) {
            case DB_ONLY -> "DB 만 (캐시 없음)";
            case CACHE -> "캐시 hit (상품별 버전, 스크립트 읽기)";
        };
        return String.format("| %s | %s | %d | %d | %.3f | %.3f | %.3f | %.3f | %.3f |",
            name, scene.roundTrips(), statements, sorted.length, mean / 1e6, percentile(sorted, 0.50),
            percentile(sorted, 0.95), percentile(sorted, 0.99), sorted[sorted.length - 1] / 1e6);
    }

    private static double percentile(long[] sorted, double p) {
        int index = (int) Math.ceil(p * sorted.length) - 1;
        return sorted[Math.max(index, 0)] / 1e6;
    }
}
