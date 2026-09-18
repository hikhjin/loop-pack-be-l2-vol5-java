package com.loopers.domain.product;

import com.loopers.support.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ProductErrorCode implements ErrorCode {
    OUT_OF_STOCK("재고가 부족합니다."),
    INVALID_STOCK("재고는 0 이상이어야 합니다.");

    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}
