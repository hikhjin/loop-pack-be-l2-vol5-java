package com.loopers.domain.product.repository;

import com.loopers.domain.product.entity.ProductLikeCount;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** 원자적 갱신 · 잠금은 실제 DB 의 동시성 테스트가 확인함 */
public class FakeProductLikeCountRepository implements ProductLikeCountRepository {

    private final Map<Long, ProductLikeCount> likeCounts = new LinkedHashMap<>();
    private long sequence = 0L;

    @Override
    public ProductLikeCount save(ProductLikeCount likeCount) {
        ReflectionTestUtils.setField(likeCount, "id", ++sequence);
        likeCounts.put(likeCount.getProductId(), likeCount);
        return likeCount;
    }

    @Override
    public int increase(Long productId, ZonedDateTime updatedAt) {
        return add(productId, 1);
    }

    @Override
    public int decrease(Long productId, ZonedDateTime updatedAt) {
        return add(productId, -1);
    }

    @Override
    public Optional<Long> findLikeCount(Long productId) {
        return Optional.ofNullable(likeCounts.get(productId)).map(ProductLikeCount::getLikeCount);
    }

    private int add(Long productId, long delta) {
        ProductLikeCount likeCount = likeCounts.get(productId);
        if (likeCount == null) {
            return 0;
        }
        ReflectionTestUtils.setField(likeCount, "likeCount", likeCount.getLikeCount() + delta);
        return 1;
    }
}
