package com.loopers.domain.product.repository;

import com.loopers.domain.product.entity.ProductLikeCount;
import java.time.ZonedDateTime;
import java.util.Optional;

public interface ProductLikeCountRepository {
    ProductLikeCount save(ProductLikeCount likeCount);

    /** 읽지 않고 한 문장으로 늘림. 행 잠금은 이 문장부터 commit 까지 */
    int increase(Long productId, ZonedDateTime updatedAt);

    int decrease(Long productId, ZonedDateTime updatedAt);

    Optional<Long> findLikeCount(Long productId);
}
