package com.loopers.domain.product.vo;

import com.loopers.domain.product.entity.Product;

/** 캐시는 품절 여부만 담아 그것이 바뀔 때만 무효화하도록 함께 알림 (4주차 cache-design 3.3) */
public record StockChange(Product product, boolean soldOutChanged) {}
