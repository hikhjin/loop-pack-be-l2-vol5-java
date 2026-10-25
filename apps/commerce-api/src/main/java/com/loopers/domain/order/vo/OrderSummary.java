package com.loopers.domain.order.vo;

import com.loopers.domain.order.entity.Order;
import com.loopers.domain.order.enums.OrderStatus;
import com.loopers.domain.order.enums.PaymentMethod;

import java.time.ZonedDateTime;

/** 품목 전체 대신 개수와 첫 품목의 상품명만 담음 (설계 6.4) */
public record OrderSummary(
    Long id,
    Long userId,
    OrderStatus status,
    long totalAmount,
    Long paymentAmount,
    int itemCount,
    String representativeProductName,
    ZonedDateTime orderedAt,
    PaymentMethod paymentMethod
) {
    public static OrderSummary from(Order order) {
        return new OrderSummary(
            order.getId(),
            order.getUserId(),
            order.getStatus(),
            order.getTotalAmount(),
            order.getPaymentAmount(),
            order.getItems().size(),
            order.getItems().get(0).getProductName(),
            order.getCreatedAt(),
            order.getPaymentMethod()
        );
    }
}
