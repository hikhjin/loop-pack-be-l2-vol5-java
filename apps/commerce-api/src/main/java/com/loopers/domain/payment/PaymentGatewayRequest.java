package com.loopers.domain.payment;

/**
 * PG 생성 요청에 필요한 값. 카드 정보는 이 요청에만 쓰고 저장하지 않음 (5주차 설계 2.2).
 * 로그 · 예외 메시지로 새지 않도록 toString 에서 카드 번호를 가림
 */
public record PaymentGatewayRequest(String providerOrderId, String cardType, String cardNo, long amount) {

    @Override
    public String toString() {
        return "PaymentGatewayRequest[providerOrderId=" + providerOrderId
            + ", cardType=" + cardType + ", cardNo=****, amount=" + amount + "]";
    }
}
