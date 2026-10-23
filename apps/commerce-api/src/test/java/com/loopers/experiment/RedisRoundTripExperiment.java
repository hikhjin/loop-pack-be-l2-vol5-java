package com.loopers.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.config.ProductCacheRedisConfig;
import com.loopers.config.redis.RedisNodeInfo;
import com.loopers.config.redis.RedisProperties;
import com.loopers.domain.product.ProductView;
import com.loopers.utils.RedisCleanUp;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.ReadFrom;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisStaticMasterReplicaConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 상품 상세 캐시의 무효화 방식(키 삭제 vs 상품별 버전)을 고르기 전에, hit 한 번에 드는 Redis 왕복 비용만 따로 잰다 (4주차).
 * 제품 코드가 아니며 결과는 설계 문서의 근거로 씀.
 *
 * <ul>
 *   <li>상품 캐시의 master 연결(ProductCacheRedisConfig)과, 측정 당시 목록 캐시가 데이터를 읽던 replica 우선 연결로 읽는다.
 *       replica 우선 연결은 목록 · 상세가 모두 master 에서 읽게 된 뒤 제품 코드에서 지웠으므로, 같은 설정(대기 한도 100ms)으로 여기서 만든다</li>
 *   <li>값은 상세 한 건 크기의 ProductView JSON 이다. 역직렬화 · MockMvc 는 포함하지 않는다</li>
 *   <li>세 방식을 한 회차에 한 번씩, 회차마다 순서를 돌려 가며 잰다. 시간에 따른 흔들림이 한 방식에 몰리지 않게 하기 위함</li>
 *   <li>Redis 는 이 실험 전용 단일 노드 컨테이너다. master 와 replica 우선 연결이 같은 노드를 가리킨다</li>
 * </ul>
 * 실행: RUN_EXPERIMENT=true ./gradlew :apps:commerce-api:test --tests '*RedisRoundTripExperiment'
 */
@SpringBootTest(properties = "spring.jpa.show-sql=false")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "RUN_EXPERIMENT", matches = "true")
class RedisRoundTripExperiment {

    private static final Path REPORT = Path.of("build", "experiment", "redis-round-trip.md");
    private static final int WARMUP_ROUNDS = 2_000;
    private static final int MEASURED_ROUNDS = 10_000;

    private static final long PRODUCT_ID = 97L;
    private static final String VERSION = "1760000000000";
    private static final String VERSION_KEY = "product-detail:version:" + PRODUCT_ID;
    private static final String DATA_KEY_PREFIX = "product-detail:v1:" + PRODUCT_ID + ":ver=";
    private static final String VERSIONED_DATA_KEY = DATA_KEY_PREFIX + VERSION;
    private static final String PLAIN_DATA_KEY = "product-detail:v1:" + PRODUCT_ID;

    /** 버전을 읽고 그 버전의 데이터 키를 같은 호출 안에서 읽는다. 키를 스크립트 안에서 만들므로 단일 노드에서만 쓸 수 있다 */
    private static final RedisScript<String> VERSIONED_GET = new DefaultRedisScript<>(
        "local v = redis.call('GET', KEYS[1]) if not v then return false end return redis.call('GET', ARGV[1] .. v)",
        String.class);

    private static final String LATENCY_HEADER = "| 방식 | Redis 왕복 | 표본 | 평균(ms) | p50(ms) | p95(ms) | p99(ms) | 최대(ms) |\n"
        + "|---|---|---|---|---|---|---|---|";

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

    @Autowired
    @Qualifier(ProductCacheRedisConfig.MASTER_TEMPLATE)
    private StringRedisTemplate master;

    @Autowired
    private RedisProperties redisProperties;

    private LettuceConnectionFactory replicaPreferredConnection;
    private StringRedisTemplate replicaPreferred;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RedisCleanUp redisCleanUp;

    private String json;

    private record Scene(String name, String roundTrips, Supplier<String> read) {}

    @BeforeAll
    void setUp() throws IOException {
        replicaPreferredConnection = replicaPreferredConnection(redisProperties);
        replicaPreferredConnection.afterPropertiesSet();
        replicaPreferred = new StringRedisTemplate(replicaPreferredConnection);
        redisCleanUp.truncateAll();
        json = objectMapper.writeValueAsString(new ProductView(PRODUCT_ID, "product-97", 3_689L, 13, 250L, 98L,
            "brand-98", "brand-98 description"));
        master.opsForValue().set(VERSION_KEY, VERSION);
        master.opsForValue().set(VERSIONED_DATA_KEY, json);
        master.opsForValue().set(PLAIN_DATA_KEY, json);
    }

    @AfterAll
    void cleanUp() {
        redisCleanUp.truncateAll();
        replicaPreferredConnection.destroy();
    }

    /** 측정 당시 ProductCacheRedisConfig 의 replica 우선 연결과 같은 설정 */
    private static LettuceConnectionFactory replicaPreferredConnection(RedisProperties properties) {
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
            .readFrom(ReadFrom.REPLICA_PREFERRED)
            .commandTimeout(Duration.ofMillis(100))
            .clientOptions(ClientOptions.builder()
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .build())
            .build();
        RedisNodeInfo master = properties.master();
        RedisStaticMasterReplicaConfiguration serverConfig = new RedisStaticMasterReplicaConfiguration(master.host(), master.port());
        serverConfig.setDatabase(properties.database());
        properties.replicas().forEach(replica -> serverConfig.addNode(replica.host(), replica.port()));
        return new LettuceConnectionFactory(serverConfig, clientConfig);
    }

    @Test
    void measureRoundTrips() throws IOException {
        List<Scene> scenes = List.of(
            new Scene("A. 키 삭제 방식: 데이터 키 읽기", "1",
                () -> replicaPreferred.opsForValue().get(PLAIN_DATA_KEY)),
            new Scene("C. 상품별 버전: 버전(master) → 데이터 키", "2",
                () -> replicaPreferred.opsForValue().get(DATA_KEY_PREFIX + master.opsForValue().get(VERSION_KEY))),
            new Scene("C 변형. Lua 로 버전과 데이터를 한 번에 (master)", "1",
                () -> master.execute(VERSIONED_GET, List.of(VERSION_KEY), DATA_KEY_PREFIX))
        );

        assertAll(scenes.stream().map(scene -> (Executable) () -> assertThat(scene.read().get()).as(scene.name()).isEqualTo(json)));

        long[][] nanos = new long[scenes.size()][MEASURED_ROUNDS];
        for (int round = 0; round < WARMUP_ROUNDS + MEASURED_ROUNDS; round++) {
            for (int offset = 0; offset < scenes.size(); offset++) {
                int index = (round + offset) % scenes.size();
                long start = System.nanoTime();
                scenes.get(index).read().get();
                long elapsed = System.nanoTime() - start;
                if (round >= WARMUP_ROUNDS) {
                    nanos[index][round - WARMUP_ROUNDS] = elapsed;
                }
            }
        }

        List<String> lines = new ArrayList<>();
        lines.add("# Redis 왕복 측정 (상품 상세 캐시 hit)");
        lines.add("");
        lines.add("- Redis " + REDIS.getDockerImageName() + " 단일 노드 (Testcontainers). master · replica 우선 연결이 같은 노드");
        lines.add("- 값: 상세 한 건 ProductView JSON " + json.getBytes(StandardCharsets.UTF_8).length + " bytes. 역직렬화 · MockMvc 제외");
        lines.add("- 회차마다 세 방식을 한 번씩, 순서를 돌려 가며 잼. 준비 " + WARMUP_ROUNDS + "회차 뒤 " + MEASURED_ROUNDS + "회차");
        lines.add("");
        lines.add(LATENCY_HEADER);
        for (int i = 0; i < scenes.size(); i++) {
            lines.add(latencyRow(scenes.get(i), nanos[i]));
        }
        lines.add("");
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, String.join("\n", lines));
    }

    private static String latencyRow(Scene scene, long[] nanos) {
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        double mean = Arrays.stream(sorted).average().orElse(0);
        return String.format("| %s | %s | %d | %.3f | %.3f | %.3f | %.3f | %.3f |",
            scene.name(), scene.roundTrips(), sorted.length, mean / 1e6, percentile(sorted, 0.50), percentile(sorted, 0.95),
            percentile(sorted, 0.99), sorted[sorted.length - 1] / 1e6);
    }

    private static double percentile(long[] sorted, double p) {
        int index = (int) Math.ceil(p * sorted.length) - 1;
        return sorted[Math.max(index, 0)] / 1e6;
    }
}
