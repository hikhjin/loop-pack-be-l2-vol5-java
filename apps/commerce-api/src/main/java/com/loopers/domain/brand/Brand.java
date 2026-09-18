package com.loopers.domain.brand;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "brand")
public class Brand extends BaseEntity {

    private static final int MAX_NAME_LENGTH = 20;

    private String name;
    private String description;

    protected Brand() {}

    public Brand(String name, String description) {
        validateName(name);
        this.name = name;
        this.description = description;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public boolean isDeleted() {
        return getDeletedAt() != null;
    }

    public void update(String name, String description) {
        if (isDeleted()) {
            throw new CoreException(BrandErrorCode.BRAND_NOT_FOUND);
        }
        validateName(name);
        this.name = name;
        this.description = description;
    }

    // 앞뒤 공백을 자르지 않는다. 길이에는 공백이 포함된다
    private static void validateName(String name) {
        if (name == null || name.isBlank() || name.length() > MAX_NAME_LENGTH) {
            throw new CoreException(BrandErrorCode.INVALID_BRAND_NAME);
        }
    }
}
