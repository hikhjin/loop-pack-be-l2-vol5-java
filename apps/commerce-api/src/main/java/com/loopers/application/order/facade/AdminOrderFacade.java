package com.loopers.application.order.facade;

import com.loopers.application.order.dto.OrderInfo;
import com.loopers.application.order.dto.OrderSummaryInfo;
import com.loopers.domain.order.service.OrderService;
import com.loopers.domain.order.entity.Order;
import com.loopers.domain.order.enums.OrderStatus;
import com.loopers.domain.order.vo.OrderSummary;
import com.loopers.domain.payment.entity.Payment;
import com.loopers.domain.payment.service.PaymentService;
import com.loopers.domain.user.enums.UserErrorCode;
import com.loopers.domain.user.service.UserService;
import com.loopers.support.error.CoreException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@RequiredArgsConstructor
@Component
public class AdminOrderFacade {
    private final OrderService orderService;
    private final UserService userService;
    private final PaymentService paymentService;

    /** 관리자 필터는 대상을 지정하므로 없는 사용자를 알림 (D-24) */
    @Transactional(readOnly = true)
    public Page<OrderSummaryInfo> getOrders(Long userId, OrderStatus status, Pageable pageable) {
        if (userId != null && !userService.exists(userId)) {
            throw new CoreException(UserErrorCode.USER_NOT_FOUND);
        }
        Page<OrderSummary> summaries = orderService.getOrderSummaries(userId, status, pageable);
        Map<Long, Payment> payments = paymentService.findByOrderIds(summaries.map(OrderSummary::id).getContent());
        return summaries.map(summary -> OrderSummaryInfo.of(summary, payments));
    }

    @Transactional(readOnly = true)
    public OrderInfo getOrder(Long orderId) {
        Order order = orderService.getOrder(orderId);
        return OrderInfo.from(order, paymentService.findByOrderId(order.getId()).orElse(null));
    }
}
