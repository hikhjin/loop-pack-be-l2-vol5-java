package com.loopers.domain.payment;

import java.util.ArrayList;
import java.util.List;

/**
 * HTTP 없이 Facade 의 순서를 확인하기 위한 PG 대역. 받은 생성 요청을 남기고, 정해 둔 결과를 돌려줌.
 * 실제 HTTP 형식 · 응답 매핑은 PaymentProviderContractTest 가 확인함
 */
public class FakePaymentGateway implements PaymentGateway {

    private final List<PaymentGatewayRequest> createRequests = new ArrayList<>();
    private final List<String> lookups = new ArrayList<>();
    private PaymentCreateResult nextCreateResult = new PaymentCreateResult.Accepted("TEST-TX-0001");
    private PaymentLookupResult nextLookupResult = new PaymentLookupResult.Found(List.of());

    @Override
    public PaymentCreateResult create(PaymentGatewayRequest request) {
        createRequests.add(request);
        return nextCreateResult;
    }

    @Override
    public PaymentLookupResult findByProviderOrderId(String providerOrderId) {
        lookups.add(providerOrderId);
        return nextLookupResult;
    }

    public void willCreate(PaymentCreateResult result) {
        this.nextCreateResult = result;
    }

    public void willLookup(PaymentLookupResult result) {
        this.nextLookupResult = result;
    }

    public List<PaymentGatewayRequest> createRequests() {
        return List.copyOf(createRequests);
    }

    public List<String> lookups() {
        return List.copyOf(lookups);
    }
}
