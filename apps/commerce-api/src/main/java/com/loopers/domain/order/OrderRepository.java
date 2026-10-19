package com.loopers.domain.order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

public interface OrderRepository {
    Order save(Order order);

    /** 소유자까지 조건으로 건 조회. 타인의 주문은 없는 주문과 같다 (ORD-06, 설계 6.1). */
    Optional<Order> findByIdAndUserId(Long orderId, Long userId);

    Optional<Order> findById(Long orderId);

    /** 소유자 조건 조회를 배타 잠금으로 함. 결제 시작(TX A)이 같은 주문의 동시 확정을 줄 세움 (5주차 설계 4.1, D-63) */
    Optional<Order> findByIdAndUserIdForUpdate(Long orderId, Long userId);

    /** 결과 적용(TX B)이 같은 주문의 결제 시도 · 예약 전이를 한 번만 일어나게 함 (5주차 설계 4.3, D-63) */
    Optional<Order> findByIdForUpdate(Long orderId);

    /** 주문 시각 desc, 식별자 desc. userId · status 가 null 이면 그 조건으로 거르지 않는다. */
    Page<Order> findPage(Long userId, OrderStatus status, Pageable pageable);
}
