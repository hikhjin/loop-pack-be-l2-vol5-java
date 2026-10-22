package com.loopers.infrastructure.payment.persistence;

import com.loopers.domain.payment.entity.Payment;
import com.loopers.domain.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@RequiredArgsConstructor
@Component
public class PaymentRepositoryImpl implements PaymentRepository {
    private final PaymentJpaRepository paymentJpaRepository;

    @Override
    public Payment save(Payment payment) {
        return paymentJpaRepository.save(payment);
    }

    @Override
    public Optional<Payment> findByOrderId(Long orderId) {
        return paymentJpaRepository.findByOrderId(orderId);
    }

    @Override
    public Optional<Payment> findByAttemptKey(String attemptKey) {
        return paymentJpaRepository.findByAttemptKey(attemptKey);
    }

    @Override
    public Optional<Payment> findByProviderOrderId(String providerOrderId) {
        return paymentJpaRepository.findByProviderOrderId(providerOrderId);
    }
}
