package com.loopers.domain.order.enums;

/** CONFIRMED 는 결제 완료이며 수령 후의 구매확정이 아님 (설계 5, 5주차 설계 2.1) */
public enum OrderStatus {
    DRAFT,
    /** 재고를 확보하고 PG 결과를 기다리는 중 */
    PAYMENT_PENDING,
    CONFIRMED,
    /** 결제가 확정 실패함. 같은 주문으로 다시 결제하지 않음 */
    PAYMENT_FAILED
}
