package com.loopers.domain.payment;

import java.util.Objects;

/** 조회 결과 판정(L1~L5). 저장값과의 비교를 건너뛰지 못하도록 package-private 으로 두고 Payment 만 만듦 */
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
