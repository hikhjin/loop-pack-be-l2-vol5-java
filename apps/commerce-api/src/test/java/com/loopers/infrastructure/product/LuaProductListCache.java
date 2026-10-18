package com.loopers.infrastructure.product;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.product.ProductListCache;
import com.loopers.domain.product.ProductSort;
import com.loopers.domain.product.ProductView;
import com.loopers.infrastructure.product.RedisProductListCache.CachedPage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * 실험 전용: 목록 캐시의 읽기를 상세 캐시와 같은 스크립트(VersionedCacheScript) 한 번으로 바꾼 변형 (4주차 docs/week4/detail-cache-design.md 3.1).
 * 제품 코드(RedisProductListCache)와 키 · 저장 형식 · 무효화가 같고, hit 의 Redis 왕복만 2회에서 1회로 준다.
 * 데이터를 replica 가 아니라 master 에서 읽는다. 장애 처리는 재지 않으므로 Redis 오류는 그대로 나간다
 */
public class LuaProductListCache implements ProductListCache {

    private final StringRedisTemplate master;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Duration ttl;
    private final RedisProductListCache invalidation;

    public LuaProductListCache(StringRedisTemplate master, ObjectMapper objectMapper, Clock clock, Duration ttl,
                               RedisProductListCache invalidation) {
        this.master = master;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.ttl = ttl;
        this.invalidation = invalidation;
    }

    @Override
    public Page<ProductView> getFirstPage(Long brandId, ProductSort sort, Supplier<Page<ProductView>> loader) {
        String scope = RedisProductListCache.scopeOf(brandId);
        VersionedCacheScript.Result read = VersionedCacheScript.read(master, RedisProductListCache.versionKey(scope),
            "product-list:v1:" + scope + ":ver=", ":sort=" + sort.name(), clock.millis());
        try {
            if (read.data() != null) {
                CachedPage cached = objectMapper.readValue(read.data(), CachedPage.class);
                return new PageImpl<>(cached.content(), PageRequest.of(cached.page(), cached.size()), cached.totalElements());
            }
            Page<ProductView> page = loader.get();
            CachedPage toCache = new CachedPage(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements());
            master.opsForValue().set(RedisProductListCache.dataKey(scope, read.version(), sort), objectMapper.writeValueAsString(toCache), ttl);
            return page;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void invalidate(Long brandId) {
        invalidation.invalidate(brandId);
    }
}
