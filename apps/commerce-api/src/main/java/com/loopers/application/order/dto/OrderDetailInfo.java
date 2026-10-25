package com.loopers.application.order.dto;

import com.loopers.domain.order.entity.Order;
import com.loopers.domain.order.entity.OrderItem;
import com.loopers.domain.payment.entity.Payment;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

/** 품목별 판매 여부는 고객 상세에만 있어 OrderInfo 와 따로 둠 (설계 6.4, D-42) */
public record OrderDetailInfo(
    Long id,
    String status,
    long totalAmount,
    Long paymentAmount,
    ZonedDateTime paidAt,
    ZonedDateTime orderedAt,
    List<Item> items,
    String paymentMethod,
    String paymentStatus
) {
    public record Item(Long productId, String productName, long unitPrice, int quantity, long amount, boolean onSale) {
        static Item of(OrderItem item, boolean onSale) {
            return new Item(item.getProductId(), item.getProductName(), item.getUnitPrice(), item.getQuantity(), item.getAmount(), onSale);
        }
    }

    /** activeProductIds 에 없는 상품은 판매종료로 봄. payment 는 결제 시도가 없으면 null */
    public static OrderDetailInfo of(Order order, Set<Long> activeProductIds, Payment payment) {
        return new OrderDetailInfo(
            order.getId(),
            order.getStatus().name(),
            order.getTotalAmount(),
            order.getPaymentAmount(),
            order.getPaidAt(),
            order.getCreatedAt(),
            order.getItems().stream()
                .map(item -> Item.of(item, activeProductIds.contains(item.getProductId())))
                .toList(),
            order.getPaymentMethod() == null ? null : order.getPaymentMethod().name(),
            payment == null ? null : payment.getStatus().name()
        );
    }
}
