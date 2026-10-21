package com.loopers.domain.payment;

/** 받은 값을 그대로 옮김. 일치 판단은 Payment 가 함 */
public record ProviderTransaction(
    String transactionKey,
    String providerOrderId,
    long amount,
    ProviderTransactionStatus status
) {}
