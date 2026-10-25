package com.loopers.domain.payment.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.loopers.domain.payment.entity.Payment;
import com.loopers.domain.payment.enums.ProviderTransactionStatus;
import com.loopers.domain.payment.repository.FakePaymentRepository;
import com.loopers.domain.payment.vo.PaymentCreateResult;
import com.loopers.domain.payment.vo.PaymentLookupResult;
import com.loopers.domain.payment.vo.ProviderTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

class PaymentServiceTest {

    private static final Long ORDER_ID = 42L;
    private static final long AMOUNT = 7_000L;
    private static final String TX_KEY = "TEST-TX-0042";

    private final FakePaymentRepository paymentRepository = new FakePaymentRepository();
    private final PaymentService paymentService = new PaymentService(paymentRepository,
        Clock.fixed(Instant.parse("2026-10-22T01:00:00Z"), ZoneId.of("Asia/Seoul")));
    private final Logger logger = (Logger) LoggerFactory.getLogger(PaymentService.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void setUp() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    private Payment requested() {
        return paymentRepository.save(Payment.request(ORDER_ID, "attempt-1", AMOUNT));
    }

    private static ProviderTransaction tx(String transactionKey, ProviderTransactionStatus status) {
        return new ProviderTransaction(transactionKey, Payment.providerOrderIdOf(ORDER_ID), AMOUNT, status);
    }

    private List<String> warnings() {
        return appender.list.stream()
            .filter(event -> event.getLevel() == Level.WARN)
            .map(ILoggingEvent::getFormattedMessage)
            .toList();
    }

    @DisplayName("결과를 적용할 때, (D-65)")
    @Nested
    class ConflictLog {
        @DisplayName("같은 주문의 거래가 2 건이면, 결제 · 주문 식별자와 관찰한 거래들을 warn 으로 한 번 남긴다.")
        @Test
        void warnsWithObservedTransactions_whenMultipleTransactionsAreFound() {
            // arrange
            Payment payment = requested();

            // act
            paymentService.apply(payment, new PaymentLookupResult.Found(List.of(
                tx(TX_KEY, ProviderTransactionStatus.SUCCESS), tx("TEST-TX-0043", ProviderTransactionStatus.PENDING))));

            // assert
            assertThat(warnings()).singleElement().satisfies(message -> assertAll(
                () -> assertThat(message).contains("paymentId=" + payment.getId(), "orderId=42",
                    "providerOrderId=LP-ORD-0000000042", "reason=MULTIPLE_TRANSACTIONS", "status=REQUESTED"),
                () -> assertThat(message).contains("TEST-TX-0042 LP-ORD-0000000042 SUCCESS 7000",
                    "TEST-TX-0043 LP-ORD-0000000042 PENDING 7000")
            ));
        }

        @DisplayName("처리 중 시도에 다른 거래 키의 생성 수락이 늦게 오면, 그 생성 응답을 관찰 내용으로 남긴다.")
        @Test
        void warnsWithLateCreateResult_whenItContradicts() {
            // arrange
            Payment payment = requested();
            paymentService.apply(payment, new PaymentCreateResult.Accepted(TX_KEY));

            // act
            paymentService.apply(payment, new PaymentCreateResult.Accepted("TEST-TX-9999"));

            // assert
            assertThat(warnings()).singleElement().satisfies(message ->
                assertThat(message).contains("reason=LATE_CREATE_CONTRADICTION", "status=PENDING", "TEST-TX-9999"));
        }

        @DisplayName("충돌 없이 종결하면, 로그를 남기지 않는다.")
        @Test
        void doesNotWarn_whenSettledWithoutConflict() {
            // arrange
            Payment payment = requested();

            // act
            paymentService.apply(payment, new PaymentLookupResult.Found(List.of(tx(TX_KEY, ProviderTransactionStatus.SUCCESS))));

            // assert
            assertThat(warnings()).isEmpty();
        }

        @DisplayName("이미 충돌을 기록한 시도에 이번 적용이 새 충돌을 만들지 않으면, 다시 남기지 않는다.")
        @Test
        void doesNotWarnAgain_whenNoNewConflictIsRecorded() {
            // arrange
            Payment payment = requested();
            paymentService.apply(payment, new PaymentLookupResult.Found(List.of(
                tx(TX_KEY, ProviderTransactionStatus.PENDING), tx("TEST-TX-0043", ProviderTransactionStatus.PENDING))));
            appender.list.clear();

            // act
            paymentService.apply(payment, new PaymentLookupResult.Unavailable());

            // assert
            assertThat(warnings()).isEmpty();
        }
    }
}
