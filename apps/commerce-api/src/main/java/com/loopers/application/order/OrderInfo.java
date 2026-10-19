package com.loopers.application.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.payment.Payment;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * 주문과 그 결제 시도의 상태. 결제수단 · 결제 상태는 DRAFT 에 없고, 5주차 전 POINT 주문에는 결제 상태가 없음 (5주차 설계 7).
 * paymentUnavailable 은 이번 요청에서 PG 를 조회하지 못했다는 뜻이며 저장된 상태는 그대로임
 */
public record OrderInfo(
    Long id,
    Long userId,
    String status,
    long totalAmount,
    Long paymentAmount,
    ZonedDateTime paidAt,
    ZonedDateTime orderedAt,
    List<Item> items,
    String paymentMethod,
    String paymentStatus,
    boolean paymentInProgress,
    boolean paymentUnavailable
) {
    public record Item(Long productId, String productName, long unitPrice, int quantity, long amount) {
        static Item from(OrderItem item) {
            return new Item(item.getProductId(), item.getProductName(), item.getUnitPrice(), item.getQuantity(), item.getAmount());
        }
    }

    public static OrderInfo from(Order order) {
        return from(order, null);
    }

    public static OrderInfo from(Order order, Payment payment) {
        return new OrderInfo(
            order.getId(),
            order.getUserId(),
            order.getStatus().name(),
            order.getTotalAmount(),
            order.getPaymentAmount(),
            order.getPaidAt(),
            order.getCreatedAt(),
            order.getItems().stream().map(Item::from).toList(),
            order.getPaymentMethod() == null ? null : order.getPaymentMethod().name(),
            payment == null ? null : payment.getStatus().name(),
            payment != null && !payment.getStatus().isTerminal(),
            false
        );
    }

    /** PG 를 조회하지 못해 저장된 상태를 그대로 돌려주는 경우. 결과를 지어내지 않음 (5주차 설계 5 fallback) */
    public OrderInfo withPaymentUnavailable() {
        return new OrderInfo(id, userId, status, totalAmount, paymentAmount, paidAt, orderedAt, items,
            paymentMethod, paymentStatus, paymentInProgress, true);
    }
}
