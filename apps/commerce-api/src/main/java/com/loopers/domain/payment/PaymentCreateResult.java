package com.loopers.domain.payment;

/** 생성 응답의 분류(E1~E3). 요청과의 일치 비교는 adapter 가 끝냄 */
public sealed interface PaymentCreateResult {

    /** E1. 생성 202 + PENDING, 요청과 같은 주문 · 금액 */
    record Accepted(String transactionKey) implements PaymentCreateResult {}

    /** E2. 거래를 만들지 않았다는 확정 거절 */
    record RejectedWithoutEffect() implements PaymentCreateResult {}

    /** E3. timeout · 연결 끊김 · 해석 불가 · 그 밖의 오류 · 응답 불일치 */
    record Unknown() implements PaymentCreateResult {}
}
