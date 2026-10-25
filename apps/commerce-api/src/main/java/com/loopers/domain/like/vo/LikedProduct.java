package com.loopers.domain.like.vo;

import java.time.ZonedDateTime;

public record LikedProduct(
    Long productId,
    String name,
    long price,
    int stock,
    long likeCount,
    Long brandId,
    String brandName,
    ZonedDateTime likedAt
) {}
