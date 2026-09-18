package com.loopers.domain.like;

import java.util.Optional;

public interface LikeRepository {
    Like save(Like like);

    Optional<Like> find(Long userId, Long productId);

    void delete(Like like);

    long countByProductId(Long productId);
}
