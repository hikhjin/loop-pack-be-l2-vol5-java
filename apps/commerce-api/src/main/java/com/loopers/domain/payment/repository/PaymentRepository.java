package com.loopers.domain.payment;

import java.util.Optional;

public interface PaymentRepository {
    /** 식별자 생성 방식(IDENTITY) 때문에 바로 INSERT 하므로, 유일 제약 위반이 이 호출에서 드러남 */
    Payment save(Payment payment);

    Optional<Payment> findByOrderId(Long orderId);

    Optional<Payment> findByAttemptKey(String attemptKey);
}
