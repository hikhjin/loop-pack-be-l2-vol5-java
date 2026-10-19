package com.loopers.domain.payment;

/**
 * 외부 PG 와의 경계. HTTP 형식이 아니라 관찰 결과만 돌려줌 (5주차 설계 3.2).
 * 상태를 어떻게 바꿀지는 Payment 가 전이표로 정함
 */
public interface PaymentGateway {

    /** 결제 거래 생성을 한 번만 보냄. 어떤 결과여도 다시 보내지 않음 (D-60) */
    PaymentCreateResult create(PaymentGatewayRequest request);

    /** 거래 키를 알아도 주문 단위 목록으로 조회함 (D-66) */
    PaymentLookupResult findByProviderOrderId(String providerOrderId);
}
