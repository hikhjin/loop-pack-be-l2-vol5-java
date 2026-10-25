package com.loopers.infrastructure.product.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.config.ProductCacheRedisConfig;
import com.loopers.domain.product.cache.ProductDetailCache;
import com.loopers.domain.product.vo.ProductView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** 상품 버전 키로 무효화하는 상세 캐시. 캐시 쪽 실패는 원본 결과로 대신함 (4주차 detail-cache-design 3.1) */
@Slf4j
@Component
public class RedisProductDetailCache implements ProductDetailCache {

    /** 복제 지연으로 옛 버전을 읽지 않도록 모두 master 에서 함 */
    private final StringRedisTemplate master;
    private final ObjectMapper objectMapper;
    private final CacheLoadCoordinator loadCoordinator;
    private final Clock clock;
    /** 무효화하지 않는 좋아요 수 · 브랜드 정보가 오래될 수 있는 상한이기도 함 (2, 5) */
    private final Duration ttl;

    /** commit 뒤 버전 올리기에 실패한 상품 (3.5) */
    private final Set<Long> pendingProductIds = ConcurrentHashMap.newKeySet();

    /** view 가 null 이면 없는 상품 · 삭제된 상품("없음") (3.3) */
    record CachedDetail(ProductView view) {}

    public RedisProductDetailCache(
        @Qualifier(ProductCacheRedisConfig.MASTER_TEMPLATE) StringRedisTemplate master,
        ObjectMapper objectMapper,
        CacheLoadCoordinator loadCoordinator,
        Clock clock,
        @Value("${product-detail-cache.ttl-seconds:60}") long ttlSeconds
    ) {
        this.master = master;
        this.objectMapper = objectMapper;
        this.loadCoordinator = loadCoordinator;
        this.clock = clock;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    /** 버전을 읽은 뒤에 원본을 읽으므로 늦은 채움은 옛 버전 키에만 들어감 (3.1, stampede-design 3) */
    @Override
    public Optional<ProductView> get(Long productId, Supplier<Optional<ProductView>> loader) {
        VersionedCacheScript.Result read;
        try {
            read = VersionedCacheScript.read(master, versionKey(productId), dataKeyPrefix(productId), "", clock.millis());
        } catch (RuntimeException e) {
            log.warn("상품 상세 캐시를 읽지 못해 원본으로 우회함. productId={}", productId, e);
            return loader.get();
        }

        String key = dataKey(productId, read.version());
        if (read.data() != null) {
            try {
                return Optional.ofNullable(objectMapper.readValue(read.data(), CachedDetail.class).view());
            } catch (JsonProcessingException e) {
                log.warn("상품 상세 캐시 값을 읽지 못해 원본으로 우회함. key={}", key, e);
                return loader.get();
            }
        }

        return loadCoordinator.load(key, () -> readStored(key), loader, view -> store(key, view));
    }

    /** "없음"도 값으로 봄. 읽을 수 없으면 예외로 알려 원본 조회로 넘김 */
    private Optional<Optional<ProductView>> readStored(String key) {
        String stored = master.opsForValue().get(key);
        if (stored == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Optional.ofNullable(objectMapper.readValue(stored, CachedDetail.class).view()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("상품 상세 캐시 값을 읽지 못함. key=" + key, e);
        }
    }

    private void store(String key, Optional<ProductView> view) {
        try {
            master.opsForValue().set(key, objectMapper.writeValueAsString(new CachedDetail(view.orElse(null))), ttl);
        } catch (RuntimeException | JsonProcessingException e) {
            log.warn("상품 상세 캐시에 저장하지 못함. key={}", key, e);
        }
    }

    @Override
    public void invalidate(Long productId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    increaseVersion(productId);
                }
            });
            return;
        }
        increaseVersion(productId);
    }

    /** 버전은 여러 번 올려도 해가 없어 중복을 따지지 않음 (3.5) */
    @Scheduled(fixedDelayString = "${product-detail-cache.retry-delay-ms:1000}")
    void retryPendingInvalidations() {
        for (Long productId : List.copyOf(pendingProductIds)) {
            pendingProductIds.remove(productId);
            increaseVersion(productId);
        }
    }

    // commit 뒤에 불리므로 어떤 실패도 쓰기 결과로 새지 않게 함
    private void increaseVersion(Long productId) {
        String key = versionKey(productId);
        try {
            master.opsForValue().setIfAbsent(key, String.valueOf(clock.millis()));
            master.opsForValue().increment(key);
        } catch (RuntimeException e) {
            pendingProductIds.add(productId);
            log.warn("상품 상세 캐시 버전을 올리지 못해 다시 시도함. productId={}", productId, e);
        }
    }

    boolean hasPendingInvalidation(Long productId) {
        return pendingProductIds.contains(productId);
    }

    static String versionKey(Long productId) {
        return "product-detail:version:" + productId;
    }

    static String dataKey(Long productId, long version) {
        return dataKeyPrefix(productId) + version;
    }

    private static String dataKeyPrefix(Long productId) {
        return "product-detail:v1:" + productId + ":ver=";
    }
}
