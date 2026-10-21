package com.loopers.domain.order;

/** 주문을 결제한 수단. 5주차 전 확정 주문은 모두 POINT, 이후 시작한 결제는 PG (5주차 설계 2.1) */
public enum PaymentMethod {
    POINT,
    PG,
    /** 0원 주문이라 결제하지 않음 (D-68) */
    NONE
}
