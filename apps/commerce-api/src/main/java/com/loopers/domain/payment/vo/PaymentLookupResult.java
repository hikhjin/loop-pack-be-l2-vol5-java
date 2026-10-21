package com.loopers.domain.payment.vo;

import java.util.List;

/** 거래 목록은 Payment 가 L1~L5 로 판정함. 조회 불가는 L6 */
public sealed interface PaymentLookupResult {

    /** 빈 목록도 정상 결과 */
    record Found(List<ProviderTransaction> transactions) implements PaymentLookupResult {
        public Found {
            transactions = List.copyOf(transactions);
        }
    }

    /** L6. timeout · 5xx · 회로 OPEN */
    record Unavailable() implements PaymentLookupResult {}
}
