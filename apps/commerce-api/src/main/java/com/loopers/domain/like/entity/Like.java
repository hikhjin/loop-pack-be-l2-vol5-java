package com.loopers.domain.like.entity;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 사용자–상품 관계. 중복은 유니크 키가 막음 (LIK-02).
 * 유니크 키는 user_id 가 앞이라 상품별 탐색용 product_id 인덱스를 따로 둠 (4주차 측정). 테이블 이름은 예약어를 피해 likes
 */
@Entity
@Table(
    name = "likes",
    uniqueConstraints = @UniqueConstraint(name = "uk_likes_user_product", columnNames = {"user_id", "product_id"}),
    indexes = @Index(name = "idx_likes_product", columnList = "product_id")
)
public class Like extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    protected Like() {}

    public Like(Long userId, Long productId) {
        this.userId = userId;
        this.productId = productId;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getProductId() {
        return productId;
    }
}
