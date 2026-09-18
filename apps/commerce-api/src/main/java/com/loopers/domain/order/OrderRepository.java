package com.loopers.domain.order;

import java.util.Optional;

public interface OrderRepository {
    Order save(Order order);

    /** 소유자까지 조건으로 건 조회. 타인의 주문은 없는 주문과 같다 (ORD-06, 설계 6.1). */
    Optional<Order> findByIdAndUserId(Long orderId, Long userId);
}
