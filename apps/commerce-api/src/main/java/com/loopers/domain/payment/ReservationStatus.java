package com.loopers.domain.payment;

/** 확보한 재고의 처리 상태. 결제 상태와 같은 전이에서만 바뀜 (5주차 설계 2.3, D-59) */
public enum ReservationStatus {
    /** 이 주문을 위해 가용 재고를 차감해 확보함 */
    RESERVED,
    /** 결제 성공으로 예약을 소비함. 재고를 더 차감하지 않음 */
    CONSUMED,
    /** 결제 확정 실패로 품목 수량만큼 한 번 되돌림 */
    RELEASED
}
