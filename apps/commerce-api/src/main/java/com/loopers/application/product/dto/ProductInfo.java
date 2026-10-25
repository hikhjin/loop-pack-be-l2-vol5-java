package com.loopers.application.product.dto;

import com.loopers.domain.product.entity.Product;
import com.loopers.domain.product.vo.ProductView;

public record ProductInfo(
    Long id,
    String name,
    long price,
    int stock,
    long likeCount,
    Long brandId,
    String brandName,
    String brandDescription
) {
    public static ProductInfo from(ProductView view) {
        return new ProductInfo(
            view.id(),
            view.name(),
            view.price(),
            view.stock(),
            view.likeCount(),
            view.brandId(),
            view.brandName(),
            view.brandDescription()
        );
    }

    /** 고객에게는 재고 수량 대신 품절 여부만 내보냄 (설계 2.4) */
    public boolean soldOut() {
        return Product.isSoldOut(stock);
    }
}
