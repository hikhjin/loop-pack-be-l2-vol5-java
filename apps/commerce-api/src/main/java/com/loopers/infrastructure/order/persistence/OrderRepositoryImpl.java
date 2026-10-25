package com.loopers.infrastructure.order.persistence;

import com.loopers.domain.order.entity.Order;
import com.loopers.domain.order.repository.OrderRepository;
import com.loopers.domain.order.enums.OrderStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.Optional;

@RequiredArgsConstructor
@Component
public class OrderRepositoryImpl implements OrderRepository {
    private final OrderJpaRepository orderJpaRepository;

    @Override
    public Order save(Order order) {
        return orderJpaRepository.save(order);
    }

    @Override
    public Optional<Order> findByIdAndUserId(Long orderId, Long userId) {
        return orderJpaRepository.findWithItemsByIdAndUserId(orderId, userId);
    }

    @Override
    public Optional<Order> findById(Long orderId) {
        return orderJpaRepository.findWithItemsById(orderId);
    }

    @Override
    public Optional<Order> findByIdAndUserIdForUpdate(Long orderId, Long userId) {
        return orderJpaRepository.findWithItemsByIdAndUserIdForUpdate(orderId, userId);
    }

    @Override
    public Optional<Order> findByIdForUpdate(Long orderId) {
        return orderJpaRepository.findWithItemsByIdForUpdate(orderId);
    }

    @Override
    public Page<Order> findPage(Long userId, OrderStatus status, Pageable pageable) {
        return orderJpaRepository.findPage(userId, status, pageable);
    }
}
