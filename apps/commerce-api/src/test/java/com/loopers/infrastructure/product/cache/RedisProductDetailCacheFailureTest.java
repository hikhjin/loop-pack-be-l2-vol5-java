package com.loopers.infrastructure.product.cache;

import com.loopers.application.brand.facade.BrandFacade;
import com.loopers.application.product.facade.AdminProductFacade;
import com.loopers.application.product.facade.ProductFacade;
import com.loopers.config.ProductCacheRedisConfig;
import com.loopers.domain.product.service.ProductService;
import com.loopers.domain.product.vo.ProductView;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 전용 Redis 컨테이너를 일시정지(pause)해 응답 없는 Redis 를 만들고,
 * 재처리 간격을 길게 두고 직접 호출해 재처리 전후를 결정적으로 나눔 (4주차 detail-cache-design 7)
 */
@SpringBootTest(properties = {
    "spring.jpa.properties.hibernate.generate_statistics=true",
    "product-list-cache.retry-delay-ms=3600000",
    "product-detail-cache.retry-delay-ms=3600000"
})
class RedisProductDetailCacheFailureTest {

    private static final long WAIT_SECONDS = 10;

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
    private ProductFacade productFacade;

    @Autowired
    private AdminProductFacade adminProductFacade;

    @Autowired
    private BrandFacade brandFacade;

    @Autowired
    private ProductService productService;

    @Autowired
    private RedisProductDetailCache cache;

    @Autowired
    private RedisProductListCache listCache;

    @Autowired
    @Qualifier(ProductCacheRedisConfig.MASTER_TEMPLATE)
    private StringRedisTemplate redis;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private Long productId;

    @BeforeEach
    void setUp() {
        Long brandId = brandFacade.createBrand("브랜드", null).id();
        productId = adminProductFacade.createProduct(brandId, "상품", 1_000L).id();
    }

    @AfterEach
    void tearDown() {
        resume();
        cache.retryPendingInvalidations();
        listCache.retryPendingInvalidations();
        databaseCleanUp.truncateAllTables();
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    private static void pause() {
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
    }

    private static void resume() {
        if (Boolean.TRUE.equals(REDIS.getDockerClient().inspectContainerCmd(REDIS.getContainerId()).exec().getState().getPaused())) {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
    }

    private long statementsOfDetail() {
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        productFacade.getProduct(productId);
        return statistics.getPrepareStatementCount();
    }

    @DisplayName("Redis 가 응답하지 않으면, 상세는 대기 한도 안에 DB 결과를 돌려주고 관리자 수정도 성공한다. (4)")
    @Test
    void bypassesToDatabase_andKeepsWritesSucceeding_whileRedisIsUnresponsive() {
        // arrange
        productFacade.getProduct(productId);
        pause();

        // act
        long writeStart = System.nanoTime();
        adminProductFacade.updateProduct(productId, "상품", 5_000L);
        long writeMillis = (System.nanoTime() - writeStart) / 1_000_000;
        long readStart = System.nanoTime();
        long price = productFacade.getProduct(productId).price();
        long readMillis = (System.nanoTime() - readStart) / 1_000_000;

        // assert
        assertAll(
            () -> assertThat(price).isEqualTo(5_000L),
            () -> assertThat(readMillis).isLessThan(1_000),
            () -> assertThat(writeMillis).isLessThan(1_000),
            () -> assertThat(cache.hasPendingInvalidation(productId)).isTrue()
        );
    }

    @DisplayName("무효화에 실패한 채 Redis 가 돌아오면, 재처리 전까지는 옛 가격이 보이고 재처리 뒤에는 새 가격이 보인다. (3.5, 5)")
    @Test
    void servesStaleUntilRetry_thenFresh_afterRedisRecovers() {
        // arrange
        productFacade.getProduct(productId);
        pause();
        adminProductFacade.updateProduct(productId, "상품", 5_000L);
        resume();

        // act
        long beforeRetry = productFacade.getProduct(productId).price();
        cache.retryPendingInvalidations();
        long afterRetry = productFacade.getProduct(productId).price();

        // assert
        assertAll(
            () -> assertThat(beforeRetry).isEqualTo(1_000L),
            () -> assertThat(afterRetry).isEqualTo(5_000L),
            () -> assertThat(cache.hasPendingInvalidation(productId)).isFalse()
        );
    }

    @DisplayName("Redis 가 돌아오면, 다시 캐시를 채우고 다음 조회부터 SQL 없이 돌려준다. (4)")
    @Test
    void refillsCache_afterRedisRecovers() {
        // arrange
        pause();
        productFacade.getProduct(productId);
        resume();

        // act
        long refill = statementsOfDetail();
        long hit = statementsOfDetail();

        // assert
        assertAll(
            () -> assertThat(refill).isEqualTo(1),
            () -> assertThat(hit).isZero()
        );
    }

    @DisplayName("변경 전에 DB 를 읽은 조회가 변경 commit · 버전 증가 뒤에 늦게 저장해도, 이후 조회는 새 값을 읽는다. (3.1 늦은 채움)")
    @Test
    void ignoresLateFillOfOlderRead() throws Exception {
        // arrange
        CountDownLatch oldReadDone = new CountDownLatch(1);
        CountDownLatch releaseOldRead = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            // 이전 조회: 버전을 읽고 DB 에서 옛 가격을 읽은 뒤, 저장하기 전에 멈춤
            Future<Optional<ProductView>> oldRead = executor.submit(() -> cache.get(productId, () -> {
                Optional<ProductView> view = productService.findActiveProductView(productId);
                oldReadDone.countDown();
                await(releaseOldRead);
                return view;
            }));
            assertThat(oldReadDone.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

            // act: 변경 commit 과 버전 증가가 끝난 뒤에 이전 조회가 옛 값을 저장함
            adminProductFacade.updateProduct(productId, "상품", 5_000L);
            releaseOldRead.countDown();
            Optional<ProductView> stale = oldRead.get(WAIT_SECONDS, TimeUnit.SECONDS);
            long next = productFacade.getProduct(productId).price();

            // assert
            assertAll(
                () -> assertThat(stale).get().extracting(ProductView::price).isEqualTo(1_000L),
                () -> assertThat(next).isEqualTo(5_000L)
            );
        } finally {
            releaseOldRead.countDown();
            executor.shutdownNow();
            executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("이전 조회를 재개하는 신호를 받지 못함");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
