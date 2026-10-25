package com.loopers.domain.product.vo;

import java.time.ZonedDateTime;

/** Product 는 Brand 를 내보내지 않으므로 응답의 브랜드 정보는 여기서 가져옴 (D-36) */
public record ProductWithBrand(
    Long id,
    String name,
    long price,
    int stock,
    Long brandId,
    String brandName,
    ZonedDateTime createdAt,
    ZonedDateTime updatedAt
) {}
