package com.loopers.domain.payment;

/** 주문 단위 조회로 받은 외부 거래 한 건. 받은 값을 그대로 옮기며, 일치 여부는 Payment 가 판단함 (5주차 설계 3.3) */
public record ProviderTransaction(
    String transactionKey,
    String providerOrderId,
    long amount,
    ProviderTransactionStatus status
) {}
