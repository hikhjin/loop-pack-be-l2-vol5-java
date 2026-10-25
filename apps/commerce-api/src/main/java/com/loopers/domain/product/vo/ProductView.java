package com.loopers.domain.product.vo;

/** 재고는 수량 그대로 담고, 품절 여부로 바꾸는 일은 응답을 만들 때 함 (설계 4.5) */
public record ProductView(
    Long id,
    String name,
    long price,
    int stock,
    long likeCount,
    Long brandId,
    String brandName,
    String brandDescription
) {}
