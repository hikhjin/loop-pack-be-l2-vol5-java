package com.loopers.domain.product.entity;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** 조회마다 집계하지 않도록 둔 좋아요 수. product 행과 잠금이 겹치지 않게 별도 테이블에 둠 (4주차 like-count-design) */
@Entity
@Table(
    name = "product_like_count",
    uniqueConstraints = @UniqueConstraint(name = "uk_product_like_count_product", columnNames = "product_id"),
    indexes = @Index(name = "idx_product_like_count_count", columnList = "like_count, product_id")
)
public class ProductLikeCount extends BaseEntity {

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "like_count", nullable = false)
    private long likeCount;

    protected ProductLikeCount() {}

    public ProductLikeCount(Long productId) {
        this.productId = productId;
        this.likeCount = 0;
    }

    public Long getProductId() {
        return productId;
    }

    public long getLikeCount() {
        return likeCount;
    }
}
