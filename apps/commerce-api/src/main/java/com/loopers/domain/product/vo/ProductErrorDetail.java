package com.loopers.domain.product.vo;

/** 여러 품목 중 문제가 된 상품을 실패 응답의 data 로 알림 (설계 6.4) */
public record ProductErrorDetail(Long productId) {
    public static ProductErrorDetail of(Long productId) {
        return new ProductErrorDetail(productId);
    }
}
