package com.loopers.application.order.facade;

import com.loopers.application.order.dto.OrderDetailInfo;
import com.loopers.application.order.dto.OrderInfo;
import com.loopers.application.order.dto.OrderSummaryInfo;
import com.loopers.domain.order.entity.Order;
import com.loopers.domain.order.entity.OrderItem;
import com.loopers.domain.order.vo.OrderLine;
import com.loopers.domain.order.service.OrderService;
import com.loopers.domain.order.enums.OrderStatus;
import com.loopers.domain.order.vo.OrderSummary;
import com.loopers.domain.payment.entity.Payment;
import com.loopers.domain.payment.service.PaymentService;
import com.loopers.domain.product.entity.Product;
import com.loopers.domain.product.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 주문 생성 · 조회 유스케이스 (설계 4.4, D-11). 주문 확정(결제)은 OrderPaymentFacade 가 맡음 */
@RequiredArgsConstructor
@Component
public class OrderFacade {
    private final OrderService orderService;
    private final ProductService productService;
    private final PaymentService paymentService;

    public record OrderRequestLine(Long productId, int quantity) {}

    /** 재고는 차감하지 않고 스냅샷만 찍음 (ORD-01, 설계 5.2) */
    @Transactional
    public OrderInfo createOrder(Long userId, List<OrderRequestLine> requestLines) {
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
        return OrderInfo.from(orderService.create(userId, lines));
    }

    /** 결제 상태는 한 페이지의 결제를 한 번에 조회해 붙임 (5주차 설계 7장) */
    @Transactional(readOnly = true)
    public Page<OrderSummaryInfo> getMyOrders(Long userId, OrderStatus status, Pageable pageable) {
        Page<OrderSummary> summaries = orderService.getOrderSummaries(userId, status, pageable);
        Map<Long, Payment> payments = paymentService.findByOrderIds(summaries.map(OrderSummary::id).getContent());
        return summaries.map(summary -> OrderSummaryInfo.of(summary, payments));
    }

    /** 판매 여부는 업무 규칙이 아니라 응답 구성 값이라 여기서 조합함 (설계 6.4, D-42) */
    @Transactional(readOnly = true)
    public OrderDetailInfo getMyOrder(Long userId, Long orderId) {
        Order order = orderService.getMyOrder(userId, orderId);
        List<Long> productIds = order.getItems().stream().map(OrderItem::getProductId).toList();
        Payment payment = paymentService.findByOrderId(order.getId()).orElse(null);
        return OrderDetailInfo.of(order, productService.getActiveProductIds(productIds), payment);
    }

}
