package com.loopers.interfaces.api;

import com.loopers.domain.product.ProductErrorCode;
import com.loopers.support.error.ErrorCode;
import com.loopers.support.error.ErrorType;
import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * 업무 오류 코드를 HTTP 상태로 바꾸는 한 곳. 대응표에 없는 코드는 500 으로 응답한다.
 */
public final class ErrorStatusMapper {

    private static final Map<ErrorCode, HttpStatus> STATUSES = Map.ofEntries(
        Map.entry(ErrorType.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR),
        Map.entry(ErrorType.BAD_REQUEST, HttpStatus.BAD_REQUEST),
        Map.entry(ErrorType.NOT_FOUND, HttpStatus.NOT_FOUND),
        Map.entry(ErrorType.CONFLICT, HttpStatus.CONFLICT),
        Map.entry(ProductErrorCode.OUT_OF_STOCK, HttpStatus.CONFLICT),
        Map.entry(ProductErrorCode.INVALID_STOCK, HttpStatus.BAD_REQUEST)
    );

    private ErrorStatusMapper() {}

    public static HttpStatus statusOf(ErrorCode errorCode) {
        return STATUSES.getOrDefault(errorCode, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    static boolean isMapped(ErrorCode errorCode) {
        return STATUSES.containsKey(errorCode);
    }
}
