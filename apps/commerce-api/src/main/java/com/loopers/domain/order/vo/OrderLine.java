package com.loopers.domain.order.vo;

/** 상품명 · 단가는 생성 시점의 스냅샷 */
public record OrderLine(Long productId, String productName, long unitPrice, int quantity) {}
