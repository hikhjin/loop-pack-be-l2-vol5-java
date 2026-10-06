package com.loopers.application.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.OrderService.OrderRequestLine;
import com.loopers.domain.product.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.List;

@RequiredArgsConstructor
@Component
public class OrderFacade {
    private final OrderService orderService;
    private final ProductService productService;

    public OrderInfo createOrder(Long userId, List<OrderRequestLine> lines) {
        return OrderInfo.from(orderService.create(userId, lines));
    }

    public Page<OrderSummaryInfo> getMyOrders(Long userId, OrderStatus status, Pageable pageable) {
        return orderService.getOrderSummaries(userId, status, pageable).map(OrderSummaryInfo::from);
    }

    /** 판매 여부는 업무 규칙이 아니라 응답 구성 값이라 여기서 조합함. 품목의 상품들을 한 번에 물음 (설계 6.4, D-42) */
    public OrderDetailInfo getMyOrder(Long userId, Long orderId) {
        Order order = orderService.getMyOrder(userId, orderId);
        List<Long> productIds = order.getItems().stream().map(OrderItem::getProductId).toList();
        return OrderDetailInfo.of(order, productService.getActiveProductIds(productIds));
    }

    public OrderInfo confirmOrder(Long userId, Long orderId) {
        return OrderInfo.from(orderService.confirm(userId, orderId));
    }
}
