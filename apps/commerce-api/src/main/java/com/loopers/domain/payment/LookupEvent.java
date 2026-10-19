package com.loopers.domain.payment;

import java.util.Objects;

/**
 * 주문 단위 조회 결과의 판정 (5주차 설계 2.2 L1~L5). L6(조회 불가)은 거래 목록이 없으므로 여기 없음.
 * 저장된 주문 · 금액 · 거래 키와 비교해야 정해지므로 Payment 만 만듦.
 * 패키지 밖에서 미리 판정한 값을 넘겨 비교를 건너뛰지 못하도록 package-private 으로 둠 (5주차 설계 3.3)
 */
enum LookupEvent {
    /** L1. 거래 1건, PENDING */
    PENDING(null),
    /** L2. 거래 1건, SUCCESS */
    SUCCESS(null),
    /** L3. 거래 1건, FAILED */
    FAILED(null),
    /** L4. 거래 0건 */
    NONE(null),
    /** L5. 거래 2건 이상 */
    MULTIPLE(PaymentConflict.MULTIPLE_TRANSACTIONS),
    /** L5. 주문 · 금액 불일치, 또는 저장된 거래 키와 다름 */
    MISMATCHED(PaymentConflict.MISMATCHED_TRANSACTION);

    private final PaymentConflict conflict;

    LookupEvent(PaymentConflict conflict) {
        this.conflict = conflict;
    }

    boolean isConflict() {
        return conflict != null;
    }

    PaymentConflict conflict() {
        return Objects.requireNonNull(conflict);
    }
}
