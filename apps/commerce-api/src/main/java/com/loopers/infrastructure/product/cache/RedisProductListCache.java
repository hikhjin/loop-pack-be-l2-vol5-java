package com.loopers.infrastructure.product.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.config.ProductCacheRedisConfig;
import com.loopers.domain.product.cache.ProductListCache;
import com.loopers.domain.product.enums.ProductSort;
import com.loopers.domain.product.vo.ProductView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
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

/** 범위 버전 키로 무효화하는 목록 캐시. 캐시 쪽 실패는 원본 결과로 대신함 (4주차 cache-design 4.1, 4.2) */
@Slf4j
@Component
public class RedisProductListCache implements ProductListCache {

    static final String ALL = "all";

    /** 복제 지연으로 옛 버전을 읽지 않도록 모두 master 에서 함 */
    private final StringRedisTemplate master;
    private final ObjectMapper objectMapper;
    private final CacheLoadCoordinator loadCoordinator;
    private final Clock clock;
    /** 무효화하지 않는 좋아요 수가 오래될 수 있는 상한이기도 함 (2.2, 5) */
    private final Duration ttl;

    /** commit 뒤 버전 올리기에 실패한 범위 (3.4) */
    private final Set<String> pendingScopes = ConcurrentHashMap.newKeySet();

    /** 응답을 다시 만들 수 있도록 페이지 위치와 전체 건수를 함께 담음 (2.2) */
    record CachedPage(List<ProductView> content, int page, int size, long totalElements) {}

    public RedisProductListCache(
        @Qualifier(ProductCacheRedisConfig.MASTER_TEMPLATE) StringRedisTemplate master,
        ObjectMapper objectMapper,
        CacheLoadCoordinator loadCoordinator,
        Clock clock,
        @Value("${product-list-cache.ttl-seconds:60}") long ttlSeconds
    ) {
        this.master = master;
        this.objectMapper = objectMapper;
        this.loadCoordinator = loadCoordinator;
        this.clock = clock;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    /** 버전을 읽은 뒤에 원본을 읽으므로 늦은 채움은 옛 버전 키에만 들어감 (3.1, stampede-design 3) */
    @Override
    public Page<ProductView> getFirstPage(Long brandId, ProductSort sort, Supplier<Page<ProductView>> loader) {
        String scope = scopeOf(brandId);
        VersionedCacheScript.Result read;
        try {
            read = VersionedCacheScript.read(master, versionKey(scope), dataKeyPrefix(scope), dataKeySuffix(sort), clock.millis());
        } catch (RuntimeException e) {
            log.warn("상품 목록 캐시를 읽지 못해 원본으로 우회함. scope={}", scope, e);
            return loader.get();
        }

        String key = dataKey(scope, read.version(), sort);
        if (read.data() != null) {
            try {
                return toPage(objectMapper.readValue(read.data(), CachedPage.class));
            } catch (JsonProcessingException e) {
                log.warn("상품 목록 캐시 값을 읽지 못해 원본으로 우회함. key={}", key, e);
                return loader.get();
            }
        }

        return loadCoordinator.load(key, () -> readStored(key), loader, page -> store(key, page));
    }

    /** 읽을 수 없으면 예외로 알려 원본 조회로 넘김 */
    private Optional<Page<ProductView>> readStored(String key) {
        String stored = master.opsForValue().get(key);
        if (stored == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(toPage(objectMapper.readValue(stored, CachedPage.class)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("상품 목록 캐시 값을 읽지 못함. key=" + key, e);
        }
    }

    private void store(String key, Page<ProductView> page) {
        try {
            master.opsForValue().set(key, objectMapper.writeValueAsString(toCached(page)), ttl);
        } catch (RuntimeException | JsonProcessingException e) {
            log.warn("상품 목록 캐시에 저장하지 못함. key={}", key, e);
        }
    }

    @Override
    public void invalidate(Long brandId) {
        List<String> scopes = List.of(scopeOf(brandId), ALL);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    scopes.forEach(RedisProductListCache.this::increaseVersion);
                }
            });
            return;
        }
        scopes.forEach(this::increaseVersion);
    }

    /** 버전은 여러 번 올려도 해가 없어 중복을 따지지 않음 (3.4) */
    @Scheduled(fixedDelayString = "${product-list-cache.retry-delay-ms:1000}")
    void retryPendingInvalidations() {
        for (String scope : List.copyOf(pendingScopes)) {
            pendingScopes.remove(scope);
            increaseVersion(scope);
        }
    }

    // commit 뒤에 불리므로 어떤 실패도 쓰기 결과로 새지 않게 함
    private void increaseVersion(String scope) {
        String key = versionKey(scope);
        try {
            master.opsForValue().setIfAbsent(key, String.valueOf(clock.millis()));
            master.opsForValue().increment(key);
        } catch (RuntimeException e) {
            pendingScopes.add(scope);
            log.warn("상품 목록 캐시 버전을 올리지 못해 다시 시도함. scope={}", scope, e);
        }
    }

    boolean hasPendingInvalidation(Long brandId) {
        return pendingScopes.contains(scopeOf(brandId));
    }

    static String scopeOf(Long brandId) {
        return brandId == null ? ALL : "brand=" + brandId;
    }

    static String versionKey(String scope) {
        return "product-list:version:" + scope;
    }

    static String dataKey(String scope, long version, ProductSort sort) {
        return dataKeyPrefix(scope) + version + dataKeySuffix(sort);
    }

    private static String dataKeyPrefix(String scope) {
        return "product-list:v1:" + scope + ":ver=";
    }

    private static String dataKeySuffix(ProductSort sort) {
        return ":sort=" + sort.name();
    }

    private static CachedPage toCached(Page<ProductView> page) {
        return new CachedPage(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements());
    }

    private static Page<ProductView> toPage(CachedPage cached) {
        return new PageImpl<>(cached.content(), PageRequest.of(cached.page(), cached.size()), cached.totalElements());
    }
}
