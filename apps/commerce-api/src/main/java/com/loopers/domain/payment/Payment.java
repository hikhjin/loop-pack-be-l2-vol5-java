package com.loopers.domain.payment;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * 주문 하나의 PG 결제 시도. 외부 결과를 얼마나 아는가(status)와 확보 재고의 처리(reservationStatus)를 함께 지킴 (5주차 설계 2.2, 2.3).
 * 상태는 전이표대로만 바뀌고, create 를 다시 보내게 하는 전이는 없음 (D-60).
 * 주문 행 잠금 아래에서만 바뀌므로 @Version 을 두지 않음 (D-63). 카드 정보는 보관하지 않음
 */
@Entity
@Table(name = "payment", uniqueConstraints = {
    @UniqueConstraint(name = "uk_payment_order", columnNames = "order_id"),
    @UniqueConstraint(name = "uk_payment_attempt_key", columnNames = "attempt_key"),
    @UniqueConstraint(name = "uk_payment_provider_order", columnNames = "provider_order_id")
})
public class Payment extends BaseEntity {

    public static final int MAX_ATTEMPT_KEY_LENGTH = 64;
    private static final String PROVIDER_ORDER_ID_FORMAT = "LP-ORD-%010d";

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "attempt_key", nullable = false, length = MAX_ATTEMPT_KEY_LENGTH)
    private String attemptKey;

    @Column(name = "provider_order_id", nullable = false)
    private String providerOrderId;

    /** 생성 응답이나 조회로 알게 되면 기록함. REQUESTED · UNKNOWN · REJECTED 에는 없음 */
    @Column(name = "transaction_key")
    private String transactionKey;

    @Column(nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "reservation_status", nullable = false)
    private ReservationStatus reservationStatus;

    /** 마지막으로 기록한 충돌 · 이상. 결제 상태와 따로 남김 (D-65) */
    @Enumerated(EnumType.STRING)
    @Column(name = "conflict_reason")
    private PaymentConflict conflictReason;

    @Column(name = "conflicted_at")
    private ZonedDateTime conflictedAt;

    protected Payment() {}

    private Payment(Long orderId, String attemptKey, long amount) {
        this.orderId = orderId;
        this.attemptKey = attemptKey;
        this.providerOrderId = providerOrderIdOf(orderId);
        this.amount = amount;
        this.status = PaymentStatus.REQUESTED;
        this.reservationStatus = ReservationStatus.RESERVED;
    }

    /** 발송 권한을 기록한 시도. 재고는 같은 TX 에서 이미 확보한 상태로 만듦 (5주차 설계 4.1) */
    public static Payment request(Long orderId, String attemptKey, long amount) {
        validateAttemptKey(attemptKey);
        if (amount < 0) {
            throw new CoreException(PaymentErrorCode.INVALID_PAYMENT_REQUEST);
        }
        return new Payment(orderId, attemptKey, amount);
    }

    /** 0원 주문의 시도. 시도 키 멱등성을 PG 주문과 같게 유지하려고 남김 (D-68) */
    public static Payment notRequired(Long orderId, String attemptKey) {
        validateAttemptKey(attemptKey);
        Payment payment = new Payment(orderId, attemptKey, 0L);
        payment.status = PaymentStatus.NOT_REQUIRED;
        payment.reservationStatus = ReservationStatus.CONSUMED;
        return payment;
    }

    /** 비었거나 64자를 넘으면 거절함. 주문을 다루기 전에 부를 수 있도록 따로 둠 (5주차 설계 7) */
    public static void validateAttemptKey(String attemptKey) {
        if (attemptKey == null || attemptKey.isBlank() || attemptKey.length() > MAX_ATTEMPT_KEY_LENGTH) {
            throw new CoreException(PaymentErrorCode.INVALID_PAYMENT_REQUEST);
        }
    }

    /** PG 요청과 주문 단위 조회에 같은 값을 씀. 내부 주문 42 는 LP-ORD-0000000042 (5주차 설계 2.2) */
    public static String providerOrderIdOf(Long orderId) {
        return String.format(PROVIDER_ORDER_ID_FORMAT, orderId);
    }

    public boolean hasAttemptKey(String attemptKey) {
        return this.attemptKey.equals(attemptKey);
    }

    /** 발송자가 받은 생성 응답(E1~E3)을 전이표대로 적용함 (5주차 설계 2.2) */
    public ReservationChange apply(PaymentCreateResult result, ZonedDateTime now) {
        return switch (status) {
            case REQUESTED -> applyCreateToRequested(result);
            case PENDING -> {
                applyLateCreateToPending(result, now);
                yield ReservationChange.NONE;
            }
            // UNKNOWN 은 발송자 자신의 E3 로만 들어오므로 생성 응답이 다시 오지 않음. 종결 뒤 늦은 응답은 효과 없음
            case UNKNOWN, SUCCESS, FAILED, REJECTED, NOT_REQUIRED -> ReservationChange.NONE;
        };
    }

    /** 주문 단위 조회 결과를 저장값과 비교해 L1~L6 으로 판정한 뒤 전이표대로 적용함 (5주차 설계 2.2, 3.3) */
    public ReservationChange apply(PaymentLookupResult result, ZonedDateTime now) {
        if (result instanceof PaymentLookupResult.Found found) {
            return applyLookup(found.transactions(), now);
        }
        // L6: 마지막으로 아는 상태 유지
        return ReservationChange.NONE;
    }

    private ReservationChange applyCreateToRequested(PaymentCreateResult result) {
        if (result instanceof PaymentCreateResult.Accepted accepted) {
            this.transactionKey = accepted.transactionKey();
            this.status = PaymentStatus.PENDING;
            return ReservationChange.NONE;
        }
        if (result instanceof PaymentCreateResult.RejectedWithoutEffect) {
            return settle(PaymentStatus.REJECTED);
        }
        this.status = PaymentStatus.UNKNOWN;
        return ReservationChange.NONE;
    }

    private void applyLateCreateToPending(PaymentCreateResult result, ZonedDateTime now) {
        if (result instanceof PaymentCreateResult.Accepted accepted && !accepted.transactionKey().equals(transactionKey)) {
            recordConflict(PaymentConflict.LATE_CREATE_CONTRADICTION, now);
        }
        // 거래가 있는데 "효과 없음" 응답이 온 모순
        if (result instanceof PaymentCreateResult.RejectedWithoutEffect) {
            recordConflict(PaymentConflict.LATE_CREATE_CONTRADICTION, now);
        }
        // 늦은 결과 미확인은 UNKNOWN 으로 낮추지 않음
    }

    private ReservationChange applyLookup(List<ProviderTransaction> transactions, ZonedDateTime now) {
        LookupEvent event = classify(transactions);
        return switch (status) {
            case REQUESTED, UNKNOWN -> applyLookupToUnsettled(event, transactions, now);
            case PENDING -> applyLookupToPending(event, now);
            case SUCCESS -> applyLookupToTerminal(event == LookupEvent.FAILED || event.isConflict(), now);
            case FAILED -> applyLookupToTerminal(event == LookupEvent.SUCCESS || event.isConflict(), now);
            // 거래가 없어야 할 시도에서 거래가 발견되면 모순
            case REJECTED, NOT_REQUIRED -> applyLookupToTerminal(event != LookupEvent.NONE, now);
        };
    }

    private ReservationChange applyLookupToUnsettled(LookupEvent event, List<ProviderTransaction> transactions, ZonedDateTime now) {
        return switch (event) {
            case PENDING -> {
                this.transactionKey = transactions.get(0).transactionKey();
                this.status = PaymentStatus.PENDING;
                yield ReservationChange.NONE;
            }
            case SUCCESS -> {
                this.transactionKey = transactions.get(0).transactionKey();
                yield settle(PaymentStatus.SUCCESS);
            }
            case FAILED -> {
                this.transactionKey = transactions.get(0).transactionKey();
                yield settle(PaymentStatus.FAILED);
            }
            // 발송 중이거나 발송하지 못했을 수 있음. 재발송하지 않음
            case NONE -> ReservationChange.NONE;
            case MULTIPLE, MISMATCHED -> {
                recordConflict(event.conflict(), now);
                yield ReservationChange.NONE;
            }
        };
    }

    private ReservationChange applyLookupToPending(LookupEvent event, ZonedDateTime now) {
        return switch (event) {
            case PENDING -> ReservationChange.NONE;
            case SUCCESS -> settle(PaymentStatus.SUCCESS);
            case FAILED -> settle(PaymentStatus.FAILED);
            // 키를 아는데 0건인 모순. 실제 PG 라면 조회 반영 지연일 수 있어 상태를 굳히지 않음
            case NONE -> {
                recordConflict(PaymentConflict.MISSING_KNOWN_TRANSACTION, now);
                yield ReservationChange.NONE;
            }
            case MULTIPLE, MISMATCHED -> {
                recordConflict(event.conflict(), now);
                yield ReservationChange.NONE;
            }
        };
    }

    /** 종결 뒤에는 상태를 덮어쓰지 않고, 상충하는 결과만 기록함 */
    private ReservationChange applyLookupToTerminal(boolean contradicts, ZonedDateTime now) {
        if (contradicts) {
            recordConflict(PaymentConflict.TERMINAL_CONTRADICTION, now);
        }
        return ReservationChange.NONE;
    }

    /** 결제 종결과 예약 전이를 한 곳에서만 일으켜, 예약 상태가 결제 상태와 따로 바뀌지 않게 함 (D-59) */
    private ReservationChange settle(PaymentStatus terminalStatus) {
        this.status = terminalStatus;
        if (terminalStatus == PaymentStatus.SUCCESS) {
            this.reservationStatus = ReservationStatus.CONSUMED;
            return ReservationChange.CONSUME;
        }
        this.reservationStatus = ReservationStatus.RELEASED;
        return ReservationChange.RELEASE;
    }

    private void recordConflict(PaymentConflict conflict, ZonedDateTime now) {
        this.conflictReason = conflict;
        this.conflictedAt = now;
    }

    /** 저장된 주문 · 금액 · 거래 키와 비교해 조회 결과를 L1~L5 로 나눔. 2건 이상이면 첫 거래를 고르지 않음 (5주차 설계 3.3) */
    private LookupEvent classify(List<ProviderTransaction> transactions) {
        if (transactions.isEmpty()) {
            return LookupEvent.NONE;
        }
        if (transactions.size() > 1) {
            return LookupEvent.MULTIPLE;
        }
        ProviderTransaction transaction = transactions.get(0);
        boolean matches = providerOrderId.equals(transaction.providerOrderId())
            && amount == transaction.amount()
            && transaction.transactionKey() != null
            && (transactionKey == null || transactionKey.equals(transaction.transactionKey()));
        if (!matches || transaction.status() == null) {
            return LookupEvent.MISMATCHED;
        }
        return switch (transaction.status()) {
            case PENDING -> LookupEvent.PENDING;
            case SUCCESS -> LookupEvent.SUCCESS;
            case FAILED -> LookupEvent.FAILED;
        };
    }

    public Long getOrderId() {
        return orderId;
    }

    public String getAttemptKey() {
        return attemptKey;
    }

    public String getProviderOrderId() {
        return providerOrderId;
    }

    public String getTransactionKey() {
        return transactionKey;
    }

    public long getAmount() {
        return amount;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public ReservationStatus getReservationStatus() {
        return reservationStatus;
    }

    public PaymentConflict getConflictReason() {
        return conflictReason;
    }

    public ZonedDateTime getConflictedAt() {
        return conflictedAt;
    }
}
