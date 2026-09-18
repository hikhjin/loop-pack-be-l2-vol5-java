package com.loopers.domain.product;

import com.loopers.domain.BaseEntity;
import com.loopers.domain.brand.Brand;
import com.loopers.support.error.CoreException;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "product")
public class Product extends BaseEntity {

    // 응답 조합에 쓰지 않도록 getter 를 두지 않는다 (설계 D-36)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "brand_id", nullable = false)
    private Brand brand;

    private String name;
    private long price;
    private int stock;

    protected Product() {}

    public Product(Brand brand, String name, long price) {
        this.brand = brand;
        this.name = name;
        this.price = price;
        this.stock = 0;
    }

    public String getName() {
        return name;
    }

    public long getPrice() {
        return price;
    }

    public int getStock() {
        return stock;
    }

    public void changeStock(int stock) {
        if (stock < 0) {
            throw new CoreException(ProductErrorCode.INVALID_STOCK);
        }
        this.stock = stock;
    }

    public boolean canDecrease(int quantity) {
        return quantity > 0 && quantity <= stock;
    }

    public void decrease(int quantity) {
        if (!canDecrease(quantity)) {
            throw new CoreException(ProductErrorCode.OUT_OF_STOCK);
        }
        this.stock -= quantity;
    }
}
