package com.loopers.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.config.ProductCacheRedisConfig;
import com.loopers.domain.product.ProductListCache;
import com.loopers.domain.product.ProductSort;
import com.loopers.domain.product.ProductView;
import com.loopers.infrastructure.product.LuaProductListCache;
import com.loopers.infrastructure.product.RedisProductListCache;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Page;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 목록 첫 페이지 캐시도 상세처럼 Lua 읽기(왕복 1회)로 바꿀지 판단하기 위한 측정 (4주차 docs/week4/detail-cache-design.md 3.1).
 * 제품 코드가 아니며 결과는 설계 문서의 근거로 씀.
 *
 * <ul>
 *   <li>포트(ProductListCache)를 바꿔 끼울 수 있는 구현을 @Primary 로 두고, 같은 컨텍스트에서 방식만 바꿔 같은 요청을 보낸다</li>
 *   <li>C 는 제품 코드(RedisProductListCache, 버전은 master · 데이터는 replica 우선, 왕복 2회),
 *       Lua 는 실험 전용 변형(LuaProductListCache, 둘 다 master, 왕복 1회)이다. 키 · 저장 형식이 같다</li>
 *   <li>목록 값은 상세보다 커서 데이터까지 master 가 보내는 부담이 다르다. 그래서 장면마다 데이터 키의 크기도 기록한다</li>
 *   <li>데이터는 목록 측정과 같은 seed(seed/week4-product-seed.sql)다. 회차마다 두 방식을 순서를 돌려 가며 잰다</li>
 * </ul>
 * 실행: RUN_EXPERIMENT=true ./gradlew :apps:commerce-api:test --tests '*ProductListCacheLuaExperiment'
 */
@SpringBootTest(properties = {
    "spring.jpa.show-sql=false",
    "spring.jpa.properties.hibernate.generate_statistics=true"
})
@AutoConfigureMockMvc
@Import(ProductListCacheLuaExperiment.SwitchConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "RUN_EXPERIMENT", matches = "true")
class ProductListCacheLuaExperiment {

    private static final String SEED = "seed/week4-product-seed.sql";
    private static final Path REPORT = Path.of("build", "experiment", "product-list-cache-lua.md");
    private static final int WARMUP_ROUNDS = 500;
    private static final int MEASURED_ROUNDS = 3_000;

    private static final String LATENCY_HEADER = "| 장면 | 방식 | Redis 왕복 | 요청당 SQL | 표본 | 평균(ms) | p50(ms) | p95(ms) | p99(ms) | 최대(ms) |\n"
        + "|---|---|---|---|---|---|---|---|---|---|";

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

    enum Mode { C, LUA }

    /** 실험 중 방식을 바꿔 끼우는 포트 구현. 무효화는 늘 제품 코드로 한다 */
    static final class SwitchableProductListCache implements ProductListCache {
        private final RedisProductListCache versioned;
        private final LuaProductListCache lua;
        private volatile Mode mode = Mode.C;

        SwitchableProductListCache(RedisProductListCache versioned, LuaProductListCache lua) {
            this.versioned = versioned;
            this.lua = lua;
        }

        void use(Mode mode) {
            this.mode = mode;
        }

        @Override
        public Page<ProductView> getFirstPage(Long brandId, ProductSort sort, Supplier<Page<ProductView>> loader) {
            return switch (mode) {
                case C -> versioned.getFirstPage(brandId, sort, loader);
                case LUA -> lua.getFirstPage(brandId, sort, loader);
            };
        }

        @Override
        public void invalidate(Long brandId) {
            versioned.invalidate(brandId);
        }
    }

    @TestConfiguration
    static class SwitchConfig {
        @Bean
        @Primary
        SwitchableProductListCache switchableProductListCache(
            RedisProductListCache versioned,
            @Qualifier(ProductCacheRedisConfig.MASTER_TEMPLATE) StringRedisTemplate master,
            ObjectMapper objectMapper,
            Clock clock
        ) {
            return new SwitchableProductListCache(versioned,
                new LuaProductListCache(master, objectMapper, clock, Duration.ofSeconds(60), versioned));
        }
    }

    /** brandId 가 null 이면 전체 목록 */
    private record Scene(String name, Long brandId, String sort) {}

    private static final List<Scene> SCENES = List.of(
        new Scene("브랜드 7 · price_asc", 7L, "price_asc"),
        new Scene("전체 · likes_desc", null, "likes_desc")
    );

    private static final List<Mode> MODES = List.of(Mode.C, Mode.LUA);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SwitchableProductListCache cache;

    @Autowired
    @Qualifier(ProductCacheRedisConfig.MASTER_TEMPLATE)
    private StringRedisTemplate redis;

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
        cache.use(Mode.C);
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    @Test
    void measureListHit() throws Exception {
        List<String> lines = new ArrayList<>();
        lines.add("# 목록 첫 페이지 hit 측정: 왕복 2회(C) vs Lua 읽기(왕복 1회)");
        lines.add("");
        lines.add("- 요청: `GET /api/v1/products?[brandId=]&sort=&page=1&size=20`. MockMvc 로 한 스레드에서 차례로 요청");
        lines.add("- Redis " + REDIS.getDockerImageName() + " 단일 노드 (Testcontainers). master 와 replica 우선 연결이 같은 노드");
        lines.add("- 데이터: " + SEED + " (상품 1만 개). 회차마다 두 방식을 한 번씩, 순서를 돌려 가며 잼. 준비 "
            + WARMUP_ROUNDS + "회차 뒤 " + MEASURED_ROUNDS + "회차");
        lines.add("");
        lines.add(LATENCY_HEADER);
        List<String> sizes = new ArrayList<>();

        for (Scene scene : SCENES) {
            // 확인: 두 방식의 응답이 같고, 캐시를 채운 뒤 hit 는 SQL 이 없다
            long[] statements = new long[MODES.size()];
            String expected = null;
            for (int i = 0; i < MODES.size(); i++) {
                cache.use(MODES.get(i));
                request(scene);
                statistics().clear();
                String body = request(scene);
                statements[i] = statistics().getPrepareStatementCount();
                if (expected == null) {
                    expected = body;
                }
                assertThat(body).as(scene.name() + " " + MODES.get(i)).isEqualTo(expected);
            }
            assertThat(statements).containsOnly(0);
            sizes.add("| " + scene.name() + " | " + dataBytes(scene) + " | " + expected.getBytes(StandardCharsets.UTF_8).length + " |");

            long[][] nanos = new long[MODES.size()][MEASURED_ROUNDS];
            for (int round = 0; round < WARMUP_ROUNDS + MEASURED_ROUNDS; round++) {
                for (int offset = 0; offset < MODES.size(); offset++) {
                    int index = (round + offset) % MODES.size();
                    cache.use(MODES.get(index));
                    long start = System.nanoTime();
                    request(scene);
                    long elapsed = System.nanoTime() - start;
                    if (round >= WARMUP_ROUNDS) {
                        nanos[index][round - WARMUP_ROUNDS] = elapsed;
                    }
                }
            }
            for (int i = 0; i < MODES.size(); i++) {
                lines.add(latencyRow(scene, MODES.get(i), statements[i], nanos[i]));
            }
        }

        lines.add("");
        lines.add("## 값 크기");
        lines.add("");
        lines.add("| 장면 | Redis 데이터 키(bytes) | 응답 본문(bytes) |");
        lines.add("|---|---|---|");
        lines.addAll(sizes);
        lines.add("");
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, String.join("\n", lines));
    }

    private String request(Scene scene) throws Exception {
        MockHttpServletRequestBuilder builder = get("/api/v1/products")
            .param("sort", scene.sort())
            .param("page", "1")
            .param("size", "20");
        if (scene.brandId() != null) {
            builder.param("brandId", String.valueOf(scene.brandId()));
        }
        return mvc.perform(builder)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    }

    /** 이 장면의 데이터 키 크기. 같은 범위 · 정렬의 키는 하나뿐이다 */
    private long dataBytes(Scene scene) {
        String scope = scene.brandId() == null ? "all" : "brand=" + scene.brandId();
        Set<String> keys = redis.keys("product-list:v1:" + scope + ":ver=*:sort=" + scene.sort().toUpperCase(Locale.ROOT));
        assertThat(keys).hasSize(1);
        Long size = redis.opsForValue().size(keys.iterator().next());
        return size == null ? 0 : size;
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private static String latencyRow(Scene scene, Mode mode, long statements, long[] nanos) {
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        double mean = Arrays.stream(sorted).average().orElse(0);
        String name = mode == Mode.C ? "C (버전 master → 데이터 replica 우선)" : "Lua (둘 다 master)";
        String roundTrips = mode == Mode.C ? "2" : "1";
        return String.format("| %s | %s | %s | %d | %d | %.3f | %.3f | %.3f | %.3f | %.3f |",
            scene.name(), name, roundTrips, statements, sorted.length, mean / 1e6, percentile(sorted, 0.50),
            percentile(sorted, 0.95), percentile(sorted, 0.99), sorted[sorted.length - 1] / 1e6);
    }

    private static double percentile(long[] sorted, double p) {
        int index = (int) Math.ceil(p * sorted.length) - 1;
        return sorted[Math.max(index, 0)] / 1e6;
    }
}
