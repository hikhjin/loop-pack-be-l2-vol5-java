package com.loopers.application.order;

import com.loopers.domain.order.OrderService;
import com.loopers.domain.order.OrderService.OrderRequestLine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@RequiredArgsConstructor
@Component
public class OrderFacade {
    private final OrderService orderService;

    public OrderInfo createOrder(Long userId, List<OrderRequestLine> lines) {
        return OrderInfo.from(orderService.create(userId, lines));
    }

    public OrderInfo confirmOrder(Long userId, Long orderId) {
        return OrderInfo.from(orderService.confirm(userId, orderId));
    }
}
