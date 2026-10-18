package com.loopers.infrastructure.product;

import com.loopers.config.ProductCacheRedisConfig;
import com.loopers.domain.product.ProductView;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 상품 상세 캐시를 실제 Redis 로 확인함 (4주차 docs/week4/detail-cache-design.md).
 * 원본은 호출 수를 세는 loader 로 대신한다. 조회 경로 · 쓰기 경로와의 연결은 Facade 테스트에서 확인함
 */
@SpringBootTest
class RedisProductDetailCacheTest {

    private static final long PRODUCT = 7L;
    private static final long OTHER_PRODUCT = 8L;

    @Autowired
    private RedisProductDetailCache cache;

    @Autowired
    @Qualifier(ProductCacheRedisConfig.MASTER_TEMPLATE)
    private StringRedisTemplate redis;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private Clock clock;

    @Autowired
    private RedisCleanUp redisCleanUp;

    @AfterEach
    void tearDown() {
        redisCleanUp.truncateAll();
    }

    /** 원본 대역. 부를 때마다 수를 세고 같은 결과를 돌려준다 */
    private static final class CountingLoader implements Supplier<Optional<ProductView>> {
        private final AtomicInteger calls = new AtomicInteger();
        private final Optional<ProductView> view;

        private CountingLoader(Optional<ProductView> view) {
            this.view = view;
        }

        @Override
        public Optional<ProductView> get() {
            calls.incrementAndGet();
            return view;
        }

        int calls() {
            return calls.get();
        }
    }

    private static CountingLoader found(long productId) {
        return new CountingLoader(Optional.of(new ProductView(productId, "상품" + productId, 1_000L * productId, 3, 5L, 1L, "브랜드", null)));
    }

    private static CountingLoader notFound() {
        return new CountingLoader(Optional.empty());
    }

    @DisplayName("상세를 읽을 때, ")
    @Nested
    class Get {
        @DisplayName("처음에는 원본을 읽어 저장하고, 다음에는 원본 없이 같은 결과를 돌려준다.")
        @Test
        void loadsOnce_andReturnsSameViewFromCache() {
            // arrange
            CountingLoader loader = found(PRODUCT);

            // act
            Optional<ProductView> miss = cache.get(PRODUCT, loader);
            Optional<ProductView> hit = cache.get(PRODUCT, loader);

            // assert
            assertAll(
                () -> assertThat(loader.calls()).isEqualTo(1),
                () -> assertThat(hit).isEqualTo(miss),
                () -> assertThat(hit).get().extracting(ProductView::brandDescription).isNull()
            );
        }

        @DisplayName("원본에 없으면, \"없음\"도 저장하고 다음에는 원본 없이 비어 있는 결과를 돌려준다. (3.3)")
        @Test
        void cachesNotFound() {
            // arrange
            CountingLoader loader = notFound();

            // act
            Optional<ProductView> miss = cache.get(PRODUCT, loader);
            Optional<ProductView> hit = cache.get(PRODUCT, loader);

            // assert
            assertAll(
                () -> assertThat(loader.calls()).isEqualTo(1),
                () -> assertThat(miss).isEmpty(),
                () -> assertThat(hit).isEmpty()
            );
        }

        @DisplayName("상품이 다르면, 서로 다른 캐시다.")
        @Test
        void keepsProductsSeparate() {
            // arrange
            cache.get(PRODUCT, found(PRODUCT));
            CountingLoader other = found(OTHER_PRODUCT);

            // act
            Optional<ProductView> result = cache.get(OTHER_PRODUCT, other);

            // assert
            assertAll(
                () -> assertThat(other.calls()).isEqualTo(1),
                () -> assertThat(result).get().extracting(ProductView::id).isEqualTo(OTHER_PRODUCT)
            );
        }

        @DisplayName("원본을 읽다 실패하면, 그 오류가 그대로 나가고 아무것도 저장하지 않는다.")
        @Test
        void propagatesLoaderFailure_andStoresNothing() {
            // arrange
            IllegalStateException failure = new IllegalStateException("원본 실패");

            // act
            IllegalStateException result = assertThrows(IllegalStateException.class,
                () -> cache.get(PRODUCT, () -> {
                    throw failure;
                }));
            CountingLoader loader = found(PRODUCT);
            cache.get(PRODUCT, loader);

            // assert
            assertAll(
                () -> assertThat(result).isSameAs(failure),
                () -> assertThat(loader.calls()).isEqualTo(1)
            );
        }

        @DisplayName("저장된 값을 읽을 수 없으면, 원본으로 우회한다.")
        @Test
        void bypassesUnreadableEntry() {
            // arrange
            CountingLoader loader = found(PRODUCT);
            cache.get(PRODUCT, loader);
            long version = Long.parseLong(redis.opsForValue().get(RedisProductDetailCache.versionKey(PRODUCT)));
            redis.opsForValue().set(RedisProductDetailCache.dataKey(PRODUCT, version), "{깨진 값");

            // act
            Optional<ProductView> result = cache.get(PRODUCT, loader);

            // assert
            assertAll(
                () -> assertThat(loader.calls()).isEqualTo(2),
                () -> assertThat(result).get().extracting(ProductView::id).isEqualTo(PRODUCT)
            );
        }
    }

    @DisplayName("상품 버전이 없을 때, ")
    @Nested
    class VersionInitialization {
        @DisplayName("현재 시각(ms) 이상의 값으로 만든다. 0 · 1 부터 세지 않는다. (3.5)")
        @Test
        void createsVersionFromCurrentMillis() {
            // arrange
            long before = clock.millis();

            // act
            cache.get(PRODUCT, found(PRODUCT));

            // assert
            assertThat(Long.parseLong(redis.opsForValue().get(RedisProductDetailCache.versionKey(PRODUCT))))
                .isGreaterThanOrEqualTo(before);
        }

        @DisplayName("버전 키 없이 무효화해도, 현재 시각 기준으로 만든 뒤 올린다.")
        @Test
        void invalidatesFromCurrentMillis_whenVersionIsMissing() {
            // arrange
            long before = clock.millis();

            // act
            cache.invalidate(PRODUCT);

            // assert
            assertThat(Long.parseLong(redis.opsForValue().get(RedisProductDetailCache.versionKey(PRODUCT))))
                .isGreaterThan(before);
        }
    }

    @DisplayName("무효화할 때, ")
    @Nested
    class Invalidate {
        @DisplayName("트랜잭션 밖이면 바로, 그 상품만 다시 원본을 읽게 한다.")
        @Test
        void invalidatesThatProduct_only() {
            // arrange
            CountingLoader product = found(PRODUCT);
            CountingLoader other = found(OTHER_PRODUCT);
            cache.get(PRODUCT, product);
            cache.get(OTHER_PRODUCT, other);

            // act
            cache.invalidate(PRODUCT);
            cache.get(PRODUCT, product);
            cache.get(OTHER_PRODUCT, other);

            // assert
            assertAll(
                () -> assertThat(product.calls()).isEqualTo(2),
                () -> assertThat(other.calls()).isEqualTo(1)
            );
        }

        @DisplayName("\"없음\"이 저장된 상품도, 무효화하면 다시 원본을 읽는다. (3.3 상품 생성)")
        @Test
        void invalidatesNotFound() {
            // arrange
            cache.get(PRODUCT, notFound());
            CountingLoader created = found(PRODUCT);

            // act
            cache.invalidate(PRODUCT);
            Optional<ProductView> result = cache.get(PRODUCT, created);

            // assert
            assertAll(
                () -> assertThat(created.calls()).isEqualTo(1),
                () -> assertThat(result).isPresent()
            );
        }

        @DisplayName("트랜잭션 안이면 commit 뒤에 한다. commit 전에는 이전 캐시를 그대로 읽는다.")
        @Test
        void invalidatesAfterCommit() {
            // arrange
            CountingLoader loader = found(PRODUCT);
            cache.get(PRODUCT, loader);

            // act
            transactionTemplate.executeWithoutResult(status -> {
                cache.invalidate(PRODUCT);
                cache.get(PRODUCT, loader);
            });
            int callsBeforeCommit = loader.calls();
            cache.get(PRODUCT, loader);

            // assert
            assertAll(
                () -> assertThat(callsBeforeCommit).isEqualTo(1),
                () -> assertThat(loader.calls()).isEqualTo(2)
            );
        }

        @DisplayName("트랜잭션이 rollback 되면 하지 않는다.")
        @Test
        void doesNotInvalidate_whenRolledBack() {
            // arrange
            CountingLoader loader = found(PRODUCT);
            cache.get(PRODUCT, loader);

            // act
            transactionTemplate.executeWithoutResult(status -> {
                cache.invalidate(PRODUCT);
                status.setRollbackOnly();
            });
            cache.get(PRODUCT, loader);

            // assert
            assertAll(
                () -> assertThat(loader.calls()).isEqualTo(1),
                () -> assertThat(cache.hasPendingInvalidation(PRODUCT)).isFalse()
            );
        }
    }
}
