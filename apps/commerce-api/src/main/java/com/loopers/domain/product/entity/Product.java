package com.loopers.domain.product.entity;

import com.loopers.domain.product.enums.ProductErrorCode;
import com.loopers.domain.product.vo.ProductErrorDetail;
import com.loopers.domain.BaseEntity;
import com.loopers.domain.brand.entity.Brand;
import com.loopers.domain.brand.enums.BrandErrorCode;
import com.loopers.support.error.CoreException;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** (brand_id, deleted_at) 인덱스로 브랜드의 살아 있는 상품 건수를 인덱스만으로 셈 (4주차 product-query-brand-index) */
@Entity
@Table(name = "product", indexes = @Index(name = "idx_product_brand_deleted", columnList = "brand_id, deleted_at"))
public class Product extends BaseEntity {

    private static final int MAX_NAME_LENGTH = 50;
    private static final long MAX_PRICE = 100_000_000L;

    // 응답 조합에 쓰지 않도록 getter 를 두지 않음 (D-36)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "brand_id", nullable = false)
    private Brand brand;

    private String name;
    private long price;
    private int stock;

    protected Product() {}

    // 브랜드 없이 존재할 수 없다는 불변식의 생성 입구 (BRD-02)
    public Product(Brand brand, String name, long price) {
        if (brand == null || brand.isDeleted()) {
            throw new CoreException(BrandErrorCode.BRAND_NOT_FOUND);
        }
        validateName(name);
        validatePrice(price);
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

    public boolean isDeleted() {
        return getDeletedAt() != null;
    }

    /** 응답의 품절 여부와 캐시 무효화가 이 기준 하나를 씀 (4주차 cache-design 3.3) */
    public static boolean isSoldOut(int stock) {
        return stock == 0;
    }

    public boolean isSoldOut() {
        return isSoldOut(stock);
    }

    /** 캐시 무효화 범위용. Brand 를 돌려주지 않고, 지연 로딩이라도 브랜드 행을 조회하지 않음 (D-36) */
    public Long getBrandId() {
        return brand.getId();
    }

    // 브랜드는 바꾸지 않음 (PRD-03)
    public void update(String name, long price) {
        validateNotDeleted();
        validateName(name);
        validatePrice(price);
        this.name = name;
        this.price = price;
    }

    public void changeStock(int stock) {
        validateNotDeleted();
        updateStock(stock);
    }

    /** 여러 품목 중 어느 상품인지 알 수 있도록 식별자를 담아 거절함 (PRD-06, 설계 6.4) */
    public void decrease(int quantity) {
        if (quantity <= 0 || quantity > stock) {
            throw new CoreException(ProductErrorCode.OUT_OF_STOCK, null, ProductErrorDetail.of(getId()));
        }
        updateStock(stock - quantity);
    }

    /** 확보 뒤 삭제된 상품에도 반환하며, 삭제 여부는 바꾸지 않음 */
    public void release(int quantity) {
        if (quantity <= 0) {
            throw new CoreException(ProductErrorCode.INVALID_STOCK);
        }
        try {
            updateStock(Math.addExact(stock, quantity));
        } catch (ArithmeticException e) {
            throw new CoreException(ProductErrorCode.INVALID_STOCK);
        }
    }

    // "재고는 0 이상"(PRD-05)을 지키는 한 곳
    private void updateStock(int newStock) {
        if (newStock < 0) {
            throw new CoreException(ProductErrorCode.INVALID_STOCK);
        }
        this.stock = newStock;
    }

    private void validateNotDeleted() {
        if (isDeleted()) {
            throw new CoreException(ProductErrorCode.PRODUCT_NOT_FOUND);
        }
    }

    // 앞뒤 공백을 자르지 않아 길이에 공백이 포함됨 (설계 6.5)
    private static void validateName(String name) {
        if (name == null || name.isBlank() || name.length() > MAX_NAME_LENGTH) {
            throw new CoreException(ProductErrorCode.INVALID_PRODUCT_NAME);
        }
    }

    private static void validatePrice(long price) {
        if (price < 0 || price > MAX_PRICE) {
            throw new CoreException(ProductErrorCode.INVALID_PRICE);
        }
    }
}
