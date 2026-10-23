package com.loopers.infrastructure.product;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.config.ProductCacheRedisConfig;
import com.loopers.domain.product.ProductDetailCache;
import com.loopers.domain.product.ProductView;
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

/**
 * 상품 상세의 Redis 캐시 (4주차 docs/week4/detail-cache-design.md).
 * 목록 캐시(RedisProductListCache)와 같은 방식으로, 데이터 키에 상품 버전을 넣고 무효화는 버전을 올려 옛 키를 버린다.
 * 목록과 달리 버전과 데이터를 스크립트 한 번으로 읽는다 (VersionedCacheScript, 3.1).
 * 캐시 쪽의 어떤 실패도 원본 결과로 대신하고 쓰기 결과에 영향을 주지 않는다. 원본(loader)의 오류는 감싸지 않는다
 */
@Slf4j
@Component
public class RedisProductDetailCache implements ProductDetailCache {

    /** 버전 · 데이터 읽기와 저장 · 버전 올리기를 모두 master 에서 한다 (VersionedCacheScript) */
    private final StringRedisTemplate master;
    private final ObjectMapper objectMapper;
    /** miss 뒤 원본 조회를 데이터 키마다 한 번으로 모은다 (stampede-design.md) */
    private final CacheLoadCoordinator loadCoordinator;
    private final Clock clock;
    /** 데이터 키의 수명. 무효화하지 않는 좋아요 수 · 브랜드 정보가 오래된 값일 수 있는 상한이기도 하다 (2, 5) */
    private final Duration ttl;

    /** commit 뒤 버전 올리기에 실패한 상품. 일정에 따라 다시 올린다 (3.5) */
    private final Set<Long> pendingProductIds = ConcurrentHashMap.newKeySet();

    /** 저장 형식. view 가 null 이면 없는 상품 · 삭제된 상품("없음")이다 (2, 3.3) */
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

    /**
     * 버전 · 데이터 읽기(스크립트 한 번) → (miss) 원본 읽기 → 읽은 버전에 저장. 원본의 오류는 감싸지 않고 그대로 나간다 (4).
     * 버전을 읽은 뒤에 원본을 읽으므로 늦은 채움은 옛 버전 키에만 들어간다 (3.1).
     * miss 뒤 원본 조회는 같은 데이터 키끼리 앱 안 · 서버 사이에서 한 번으로 모은다 (stampede-design.md 3)
     */
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

    /** 다른 서버가 저장한 값. "없음"도 값으로 본다. 읽을 수 없으면 예외로 알려 원본 조회로 넘긴다 */
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

    /**
     * 실패한 무효화를 다시 시도한다. 버전은 여러 번 올려도 해가 없어 중복을 따지지 않는다 (3.5).
     * 간격은 기본 1초이며, 장애 테스트가 재처리 시점을 직접 정할 수 있도록 설정으로 둔다
     */
    @Scheduled(fixedDelayString = "${product-detail-cache.retry-delay-ms:1000}")
    void retryPendingInvalidations() {
        for (Long productId : List.copyOf(pendingProductIds)) {
            pendingProductIds.remove(productId);
            increaseVersion(productId);
        }
    }

    // commit 뒤에 불리므로 어떤 실패도 쓰기 결과로 새어 나가지 않게 한다
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
