package com.loopers.application.brand;

import com.loopers.application.product.AdminProductFacade;
import com.loopers.domain.brand.Brand;
import com.loopers.support.error.CoreException;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 브랜드 행을 함께 쓰는 경로의 동시 실행을 실제 DB 로 확인함 (BRD-02, 3주차 설계 4.2, 6.4).
 * 작업의 시작만 맞추며, 어느 순서로 겹치더라도 불변식이 지켜지는지 확인함
 */
@SpringBootTest
class BrandConcurrencyTest {

    private static final int CONCURRENT_REQUESTS = 5;
    private static final String SUCCESS = "SUCCESS";
    private static final String BRAND_NOT_FOUND = "BRAND_NOT_FOUND";

    @Autowired
    private BrandFacade brandFacade;

    @Autowired
    private AdminProductFacade adminProductFacade;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("브랜드 삭제와 같은 브랜드의 상품 등록이 겹쳐도, 삭제된 브랜드에 살아 있는 상품이 남지 않는다. (선택 확장)")
    @Test
    void leavesNoActiveProductInDeletedBrand_whenDeleteAndCreateRunConcurrently() throws Exception {
        // arrange
        Brand brand = persistBrand();
        List<Callable<String>> tasks = new ArrayList<>();
        tasks.add(outcomeOf(() -> brandFacade.deleteBrand(brand.getId())));
        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            String name = "상품" + i;
            tasks.add(outcomeOf(() -> adminProductFacade.createProduct(brand.getId(), name, 1_000L)));
        }

        // act
        List<String> outcomes = runConcurrently(tasks);

        // assert
        assertAll(
            () -> assertThat(outcomes.get(0)).isEqualTo(SUCCESS),
            () -> assertThat(outcomes.subList(1, outcomes.size())).allSatisfy(outcome -> assertThat(outcome).isIn(SUCCESS, BRAND_NOT_FOUND)),
            () -> assertThat(isBrandDeleted(brand.getId())).isTrue(),
            () -> assertThat(countActiveProducts(brand.getId())).isZero(),
            () -> assertThat(countAllProducts(brand.getId())).isEqualTo(outcomes.stream().skip(1).filter(SUCCESS::equals).count())
        );
    }

    @DisplayName("브랜드 삭제와 수정이 겹쳐도, 삭제된 브랜드가 되살아나지 않는다.")
    @Test
    void keepsBrandDeleted_whenDeleteAndUpdateRunConcurrently() throws Exception {
        // arrange
        Brand brand = persistBrand();
        List<Callable<String>> tasks = new ArrayList<>();
        tasks.add(outcomeOf(() -> brandFacade.deleteBrand(brand.getId())));
        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            String name = "새 브랜드" + i;
            tasks.add(outcomeOf(() -> brandFacade.updateBrand(brand.getId(), name, null)));
        }

        // act
        List<String> outcomes = runConcurrently(tasks);

        // assert
        assertAll(
            () -> assertThat(outcomes.get(0)).isEqualTo(SUCCESS),
            () -> assertThat(outcomes.subList(1, outcomes.size())).allSatisfy(outcome -> assertThat(outcome).isIn(SUCCESS, BRAND_NOT_FOUND)),
            () -> assertThat(isBrandDeleted(brand.getId())).isTrue()
        );
    }

    private Brand persistBrand() {
        return transactionTemplate.execute(status -> {
            Brand brand = new Brand("브랜드", null);
            entityManager.persist(brand);
            return brand;
        });
    }

    /** 성공 · 업무 거절(오류 코드) · 기술 오류(예외 타입)를 구분해 돌려줌. 예외를 버리지 않음 */
    private static Callable<String> outcomeOf(Runnable request) {
        return () -> {
            try {
                request.run();
                return SUCCESS;
            } catch (CoreException e) {
                return e.getErrorCode().getCode();
            } catch (RuntimeException e) {
                return "TECHNICAL:" + e.getClass().getSimpleName();
            }
        };
    }

    /** 모든 작업의 시작만 맞춰 실행하고, 끝날 때까지 기다린 뒤 결과를 순서대로 돌려줌 */
    private static List<String> runConcurrently(List<Callable<String>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (Callable<String> task : tasks) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("시작 신호를 받지 못함");
                    }
                    return task.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            start.countDown();
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    /** 모든 작업이 끝난 뒤 새 트랜잭션에서 DB 를 다시 읽음 */
    private boolean isBrandDeleted(Long brandId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status ->
            entityManager.createNativeQuery("select deleted_at from brand where id = :id")
                .setParameter("id", brandId)
                .getSingleResult() != null
        ));
    }

    private long countActiveProducts(Long brandId) {
        return count("select count(*) from product where brand_id = :brandId and deleted_at is null", brandId);
    }

    private long countAllProducts(Long brandId) {
        return count("select count(*) from product where brand_id = :brandId", brandId);
    }

    private long count(String sql, Long brandId) {
        Number result = transactionTemplate.execute(status ->
            (Number) entityManager.createNativeQuery(sql).setParameter("brandId", brandId).getSingleResult()
        );
        return result.longValue();
    }
}
