package com.loopers.domain.payment;

import com.loopers.support.error.CoreException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class PaymentService {

    private final PaymentRepository paymentRepository;
    /** 충돌 기록 시각의 기준 */
    private final Clock clock;

    @Transactional(readOnly = true)
    public Optional<Payment> findByOrderId(Long orderId) {
        return paymentRepository.findByOrderId(orderId);
    }

    @Transactional(readOnly = true)
    public Payment getByOrderId(Long orderId) {
        return paymentRepository.findByOrderId(orderId)
            .orElseThrow(() -> new CoreException(PaymentErrorCode.PAYMENT_NOT_FOUND));
    }

    /** 다른 주문이 이미 쓴 시도 키면 거절함. 재고를 다루기 전에 부름 (5주차 설계 4.1) */
    @Transactional(readOnly = true)
    public void validateAttemptKeyAvailable(String attemptKey) {
        if (paymentRepository.findByAttemptKey(attemptKey).isPresent()) {
            throw new CoreException(PaymentErrorCode.PAYMENT_ATTEMPT_CONFLICT);
        }
    }

    /**
     * 발송 권한을 기록함. 미리 확인한 뒤에도 다른 주문이 같은 키로 먼저 저장하면 유일 제약이 막고,
     * 이 트랜잭션 전체(재고 확보 포함)가 rollback 됨 (5주차 설계 4.1)
     */
    @Transactional
    public Payment request(Long orderId, String attemptKey, long amount) {
        try {
            return paymentRepository.save(Payment.request(orderId, attemptKey, amount));
        } catch (DataIntegrityViolationException e) {
            throw new CoreException(PaymentErrorCode.PAYMENT_ATTEMPT_CONFLICT);
        }
    }

    @Transactional
    public ReservationChange apply(Payment payment, PaymentCreateResult result) {
        return payment.apply(result, ZonedDateTime.now(clock));
    }

    @Transactional
    public ReservationChange apply(Payment payment, PaymentLookupResult result) {
        return payment.apply(result, ZonedDateTime.now(clock));
    }
}
