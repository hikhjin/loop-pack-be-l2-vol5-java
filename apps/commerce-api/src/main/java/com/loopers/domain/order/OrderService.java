package com.loopers.domain.order;

import com.loopers.domain.point.PointService;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductService;
import com.loopers.support.error.CoreException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Component
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductService productService;
    private final PointService pointService;
    /** 만료 · 결제 시각의 기준. 테스트에서 고정할 수 있도록 주입받음 (설계 2.3) */
    private final Clock clock;

    public record OrderRequestLine(Long productId, int quantity) {}

    /** 살아 있는 상품을 요청 순서대로 조회해 스냅샷을 찍고 DRAFT 로 저장한다 (ORD-01). 차감하지 않는다. */
    @Transactional
    public Order create(Long userId, List<OrderRequestLine> requestLines) {
        Map<Long, Product> products = new LinkedHashMap<>();
        for (OrderRequestLine line : requestLines) {
            products.computeIfAbsent(line.productId(), productService::getActiveProduct);
        }
        List<OrderLine> lines = requestLines.stream()
            .map(line -> {
                Product product = products.get(line.productId());
                return new OrderLine(product.getId(), product.getName(), product.getPrice(), line.quantity());
            })
            .toList();
        return orderRepository.save(Order.draft(userId, lines, ZonedDateTime.now(clock)));
    }

    /**
     * 주문 → 상품 → 포인트 순으로 각 객체의 행동을 부르고, 규칙을 어기면 그 객체가 예외를 던짐 (설계 5.3).
     * 처음 실패에서 멈추며, 앞서 바뀐 재고 · 잔액은 트랜잭션 롤백으로 DB 에 반영되지 않음 (5.4).
     * 결제액은 주문서 합계이며 현재 가격과 비교하지 않음 (설계 2.3)
     */
    @Transactional
    public Order confirm(Long userId, Long orderId) {
        Order order = orderRepository.findByIdAndUserId(orderId, userId)
            .orElseThrow(() -> new CoreException(OrderErrorCode.ORDER_NOT_FOUND));
        ZonedDateTime now = ZonedDateTime.now(clock);
        order.validateConfirmable(now);

        // 삭제된 상품(ORD-09)을 모두 확인한 뒤 차감(ORD-10)함. 실패한 상품은 품목 순서대로 처음 것
        Map<Long, Product> products = new LinkedHashMap<>();
        for (OrderItem item : order.getItems()) {
            products.put(item.getProductId(), productService.getActiveProduct(item.getProductId()));
        }
        for (OrderItem item : order.getItems()) {
            products.get(item.getProductId()).decrease(item.getQuantity());
        }
        // 충전한 적 없는 사용자의 0원 결제는 Point 행을 만들지 않음 (D-30)
        long paymentAmount = order.getTotalAmount();
        pointService.getPoint(userId).pay(paymentAmount);
        order.confirm(paymentAmount, now);
        return order;
    }

    /** 요청자 본인의 주문만 조회한다. 타인의 주문은 없는 주문과 같다 (설계 6.1). */
    @Transactional(readOnly = true)
    public Order getMyOrder(Long userId, Long orderId) {
        return orderRepository.findByIdAndUserId(orderId, userId)
            .orElseThrow(() -> new CoreException(OrderErrorCode.ORDER_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public Order getOrder(Long orderId) {
        return orderRepository.findById(orderId)
            .orElseThrow(() -> new CoreException(OrderErrorCode.ORDER_NOT_FOUND));
    }

    /** 품목을 읽는 요약은 트랜잭션 안에서 만든다 (open-in-view 가 꺼져 있다). */
    @Transactional(readOnly = true)
    public Page<OrderSummary> getOrderSummaries(Long userId, OrderStatus status, Pageable pageable) {
        return orderRepository.findPage(userId, status, pageable).map(OrderSummary::from);
    }
}
