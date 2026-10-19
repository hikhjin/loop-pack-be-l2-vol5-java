package com.loopers.application.order;

import com.loopers.domain.payment.Payment;
import com.loopers.domain.payment.PaymentCreateResult;
import com.loopers.domain.payment.PaymentErrorCode;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PaymentGatewayRequest;
import com.loopers.domain.payment.PaymentLookupResult;
import com.loopers.domain.product.ProductDetailCache;
import com.loopers.domain.product.ProductListCache;
import com.loopers.support.error.CoreException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 주문의 PG 결제 유스케이스. 트랜잭션을 열지 않고 "로컬 TX A → TX 밖 PG 호출 → 로컬 TX B" 순서만 정함 (5주차 설계 4).
 * PG 호출 동안 DB 트랜잭션과 행 잠금을 쥐지 않으며, 생성 요청은 결과와 관계없이 다시 보내지 않음 (D-60)
 */
@RequiredArgsConstructor
@Component
public class OrderPaymentFacade {
    private final OrderPaymentSteps steps;
    private final PaymentGateway paymentGateway;
    private final ProductListCache productListCache;
    private final ProductDetailCache productDetailCache;

    /** 카드 정보는 PG 요청에만 쓰고 저장하지 않음. 로그로 새지 않도록 toString 에서 가림 (5주차 설계 2.2) */
    public record ConfirmCommand(String paymentAttemptKey, String cardType, String cardNo) {
        @Override
        public String toString() {
            return "ConfirmCommand[paymentAttemptKey=" + paymentAttemptKey + ", cardType=" + cardType + ", cardNo=****]";
        }
    }

    /**
     * 주문 확정 = PG 결제 시작 (5주차 설계 4.1). 발송 권한을 얻은 실행만 PG 에 생성을 한 번 보내고, 그 응답을 TX B 에서 적용함.
     * 같은 시도 키의 재요청 · 동시 요청은 저장된 상태만 돌려줌. 외부 결과 확인은 reconcile · callback 이 맡음
     */
    public OrderInfo confirm(Long userId, Long orderId, ConfirmCommand command) {
        validate(command);
        OrderPaymentSteps.Started started = steps.start(userId, orderId, command.paymentAttemptKey());
        // TX A 가 commit 된 뒤라 rollback 된 확보로 캐시를 지우지 않음. Redis 실패는 결제에 영향을 주지 않음 (4주차 cache-design.md 3.4)
        invalidate(started.soldOutBrandIds(), started.soldOutProductIds());
        if (!started.sendable()) {
            return started.order();
        }
        PaymentCreateResult result = paymentGateway.create(
            new PaymentGatewayRequest(started.providerOrderId(), command.cardType(), command.cardNo(), started.amount()));
        OrderPaymentSteps.Applied applied = steps.applyCreateResult(orderId, result);
        invalidate(applied.restockedBrandIds(), applied.restockedProductIds());
        return applied.order();
    }

    /**
     * 본인 미완료 시도의 기존 외부 거래를 주문 단위로 조회해 적용함 (5주차 설계 4.2). 종결된 시도는 조회하지 않음.
     * 조회하지 못하면 저장된 상태를 그대로 두고 확인 불가를 알림. 새 결제를 만들지 않음
     */
    public OrderInfo reconcile(Long userId, Long orderId) {
        OrderPaymentSteps.Snapshot snapshot = steps.snapshot(userId, orderId);
        if (snapshot.settled()) {
            return snapshot.order();
        }
        PaymentLookupResult result = paymentGateway.findByProviderOrderId(snapshot.providerOrderId());
        if (result instanceof PaymentLookupResult.Unavailable) {
            return snapshot.order().withPaymentUnavailable();
        }
        OrderPaymentSteps.Applied applied = steps.applyLookupResult(orderId, result);
        invalidate(applied.restockedBrandIds(), applied.restockedProductIds());
        return applied.order();
    }

    /** 시도 키와 합성 카드 값이 비었으면 주문을 다루기 전에 거절함 (5주차 설계 7) */
    private static void validate(ConfirmCommand command) {
        Payment.validateAttemptKey(command.paymentAttemptKey());
        if (isBlank(command.cardType()) || isBlank(command.cardNo())) {
            throw new CoreException(PaymentErrorCode.INVALID_PAYMENT_REQUEST);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void invalidate(Set<Long> brandIds, Set<Long> productIds) {
        brandIds.forEach(productListCache::invalidate);
        productIds.forEach(productDetailCache::invalidate);
    }
}
