package com.loopers.domain.order.entity;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** 상품명 · 단가는 DRAFT 생성 시점의 스냅샷이라 상품이 바뀌거나 삭제돼도 그대로 남음 (설계 2.3) */
@Entity
@Table(name = "order_items")
public class OrderItem extends BaseEntity {

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(name = "unit_price", nullable = false)
    private long unitPrice;

    @Column(nullable = false)
    private int quantity;

    protected OrderItem() {}

    OrderItem(Long productId, String productName, long unitPrice, int quantity) {
        this.productId = productId;
        this.productName = productName;
        this.unitPrice = unitPrice;
        this.quantity = quantity;
    }

    public Long getProductId() {
        return productId;
    }

    public String getProductName() {
        return productName;
    }

    public long getUnitPrice() {
        return unitPrice;
    }

    public int getQuantity() {
        return quantity;
    }

    public long getAmount() {
        return unitPrice * quantity;
    }
}
