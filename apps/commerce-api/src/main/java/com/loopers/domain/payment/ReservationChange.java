package com.loopers.domain.payment;

/**
 * 한 번의 전이가 요구하는 주문 · 재고 쪽 후속 효과. 같은 TX 안에서 호출자가 한 번만 수행함 (5주차 설계 4.3).
 * 이미 종결된 시도에는 언제나 NONE 이므로 중복 사건이 재고를 다시 바꾸지 않음
 */
public enum ReservationChange {
    /** 결제 상태만 바뀌었거나 아무것도 바뀌지 않음 */
    NONE,
    /** 주문 CONFIRMED. 재고는 추가로 차감하지 않음 */
    CONSUME,
    /** 주문 PAYMENT_FAILED + 품목 수량만큼 재고 반환 */
    RELEASE
}
