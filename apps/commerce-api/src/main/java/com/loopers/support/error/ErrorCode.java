package com.loopers.support.error;

/** HTTP 상태를 알지 않음. 상태로 바꾸는 일은 interfaces 에서 함 */
public interface ErrorCode {
    String getCode();

    String getMessage();
}
