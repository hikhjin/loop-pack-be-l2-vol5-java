package com.loopers.application.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.payment.Payment;
import com.loopers.domain.payment.PaymentCreateResult;
import com.loopers.domain.payment.PaymentErrorCode;
import com.loopers.domain.payment.PaymentLookupResult;
import com.loopers.domain.payment.PaymentService;
import com.loopers.domain.payment.ReservationChange;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductService;
import com.loopers.support.error.CoreException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * PG 결제의 로컬 트랜잭션 단계. 단계마다 트랜잭션을 새로 열고, PG 호출은 OrderPaymentFacade 가 단계 사이(TX 밖)에서 함 (5주차 설계 4).
 * 자기 호출로는 트랜잭션이 열리지 않으므로 Facade 와 다른 bean 으로 둠. 잠금 순서는 orders → product (D-63)
 */
@RequiredArgsConstructor
@Component
public class OrderPaymentSteps {
    private final OrderService orderService;
    private final ProductService productService;
    private final PaymentService paymentService;

    /** TX A 의 결과. sendable 이면 이번 실행이 발송 권한을 얻었으므로 PG 생성을 한 번 보냄 */
    public record Started(
        OrderInfo order,
        boolean sendable,
        String providerOrderId,
        long amount,
        Set<Long> soldOutBrandIds,
        Set<Long> soldOutProductIds
    ) {}

    /** TX B 의 결과. 반환으로 품절이 풀린 상품은 commit 뒤 캐시를 무효화함 */
    public record Applied(OrderInfo order, Set<Long> restockedBrandIds, Set<Long> restockedProductIds) {}

    /** 결과 확인 전의 저장 상태. 종결이면 PG 를 조회하지 않음 (5주차 설계 4.2) */
    public record Snapshot(OrderInfo order, String providerOrderId, boolean settled) {}

    /**
     * TX A: 주문 잠금 → 기존 시도 확인 → 확정 가능 검사 → 재고 확보 → 발송 권한 기록 → PAYMENT_PENDING (5주차 설계 4.1).
     * 같은 시도 키의 재요청은 저장 상태만 돌려주고, 재고 부족 · 삭제 · 만료 · 충돌이면 전체 rollback 되어 PG 를 부르지 않음
     */
    @Transactional
    public Started start(Long userId, Long orderId, String attemptKey) {
        Order order = orderService.getMyOrderForUpdate(userId, orderId);
        Optional<Payment> existing = paymentService.findByOrderId(order.getId());
        if (existing.isPresent()) {
            if (!existing.get().hasAttemptKey(attemptKey)) {
                throw new CoreException(PaymentErrorCode.PAYMENT_ATTEMPT_CONFLICT);
            }
            return new Started(OrderInfo.from(order, existing.get()), false, existing.get().getProviderOrderId(),
                existing.get().getAmount(), Set.of(), Set.of());
        }
        // 실패한 검사가 재고 · 주문을 바꾸기 전에 끝나도록, 바꾸는 행동보다 앞에 둠 (만료 → 상품 삭제 여부 → 재고, D-16)
        orderService.validateConfirmable(order);
        paymentService.validateAttemptKeyAvailable(attemptKey);
        Map<Long, Product> products = productService.getActiveProductsForUpdate(productIdsOf(order));
        // 목록 · 상세 캐시는 품절 여부만 내보내므로 이번 확보로 품절이 된 상품과 그 브랜드만 무효화함 (4주차 cache-design.md 3.3)
        Set<Long> soldOutBrandIds = new LinkedHashSet<>();
        Set<Long> soldOutProductIds = new LinkedHashSet<>();
        for (OrderItem item : order.getItems()) {
            Product product = products.get(item.getProductId());
            boolean wasSoldOut = product.isSoldOut();
            product.decrease(item.getQuantity());
            if (!wasSoldOut && product.isSoldOut()) {
                soldOutBrandIds.add(product.getBrandId());
                soldOutProductIds.add(product.getId());
            }
        }
        // 0원 주문은 PG 를 부르지 않고 여기서 확정함 (D-68)
        if (order.getTotalAmount() == 0) {
            Payment payment = paymentService.recordNotRequired(order.getId(), attemptKey);
            orderService.confirmWithoutPayment(order);
            return new Started(OrderInfo.from(order, payment), false, payment.getProviderOrderId(), 0L,
                soldOutBrandIds, soldOutProductIds);
        }
        Payment payment = paymentService.request(order.getId(), attemptKey, order.getTotalAmount());
        orderService.startPayment(order);
        return new Started(OrderInfo.from(order, payment), true, payment.getProviderOrderId(), payment.getAmount(),
            soldOutBrandIds, soldOutProductIds);
    }

    /** TX B: 발송자가 받은 생성 응답(E1~E3)을 적용함 (5주차 설계 4.3) */
    @Transactional
    public Applied applyCreateResult(Long orderId, PaymentCreateResult result) {
        Order order = orderService.getOrderForUpdate(orderId);
        Payment payment = paymentService.getByOrderId(orderId);
        ReservationChange change = paymentService.apply(payment, result);
        return settle(order, payment, change);
    }

    /** 결과 확인 전 저장 상태를 읽음. 결제 시도가 없으면(DRAFT · 이전 POINT 주문) PAYMENT_NOT_FOUND */
    @Transactional(readOnly = true)
    public Snapshot snapshot(Long userId, Long orderId) {
        Order order = orderService.getMyOrder(userId, orderId);
        Payment payment = paymentService.getByOrderId(order.getId());
        return new Snapshot(OrderInfo.from(order, payment), payment.getProviderOrderId(), payment.getStatus().isTerminal());
    }

    /** TX B: 주문 단위 조회 결과를 저장값과 비교해 적용함. 판정과 전이는 Payment 가 함 (5주차 설계 3.3, 4.3) */
    @Transactional
    public Applied applyLookupResult(Long orderId, PaymentLookupResult result) {
        Order order = orderService.getOrderForUpdate(orderId);
        Payment payment = paymentService.getByOrderId(orderId);
        ReservationChange change = paymentService.apply(payment, result);
        return settle(order, payment, change);
    }

    /** 결제 종결과 같은 TX 에서 주문 · 재고 후속 효과를 한 번만 일으킴. 이미 종결된 시도는 NONE 이라 아무것도 하지 않음 (D-59) */
    private Applied settle(Order order, Payment payment, ReservationChange change) {
        Set<Long> restockedBrandIds = new LinkedHashSet<>();
        Set<Long> restockedProductIds = new LinkedHashSet<>();
        switch (change) {
            case CONSUME -> orderService.confirmPaid(order, payment.getAmount());
            case RELEASE -> {
                Map<Long, Product> products = productService.getProductsForUpdate(productIdsOf(order));
                for (OrderItem item : order.getItems()) {
                    Product product = products.get(item.getProductId());
                    boolean wasSoldOut = product.isSoldOut();
                    product.release(item.getQuantity());
                    if (wasSoldOut && !product.isSoldOut()) {
                        restockedBrandIds.add(product.getBrandId());
                        restockedProductIds.add(product.getId());
                    }
                }
                orderService.failPayment(order);
            }
            case NONE -> { }
        }
        return new Applied(OrderInfo.from(order, payment), restockedBrandIds, restockedProductIds);
    }

    private static List<Long> productIdsOf(Order order) {
        return order.getItems().stream().map(OrderItem::getProductId).toList();
    }
}
