package com.loopers.domain.payment;

import java.util.List;

/** 주문 단위 조회(?orderId=)의 결과. 거래 목록은 L1~L5 로, 조회 불가는 L6 으로 쓰임 (5주차 설계 2.2, D-66) */
public sealed interface PaymentLookupResult {

    /** 조회에 성공함. 빈 목록도 정상 결과 */
    record Found(List<ProviderTransaction> transactions) implements PaymentLookupResult {
        public Found {
            transactions = List.copyOf(transactions);
        }
    }

    /** L6. timeout · 5xx · 회로 OPEN */
    record Unavailable() implements PaymentLookupResult {}
}
