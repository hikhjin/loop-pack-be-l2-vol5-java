package com.loopers.application.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderLine;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductService;
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

    public record OrderRequestLine(Long productId, int quantity) {}

    /** 살아 있는 상품을 요청 순서대로 조회해 스냅샷을 찍고 DRAFT 로 저장함 (ORD-01, 설계 5.2). 차감하지 않음 */
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

    public Page<OrderSummaryInfo> getMyOrders(Long userId, OrderStatus status, Pageable pageable) {
        return orderService.getOrderSummaries(userId, status, pageable).map(OrderSummaryInfo::from);
    }

    /** 판매 여부는 업무 규칙이 아니라 응답 구성 값이라 여기서 조합함. 품목의 상품들을 한 번에 물음 (설계 6.4, D-42) */
    @Transactional(readOnly = true)
    public OrderDetailInfo getMyOrder(Long userId, Long orderId) {
        Order order = orderService.getMyOrder(userId, orderId);
        List<Long> productIds = order.getItems().stream().map(OrderItem::getProductId).toList();
        return OrderDetailInfo.of(order, productService.getActiveProductIds(productIds));
    }

}
