package com.loopers.domain.payment;

/**
 * 외부 결과를 얼마나 아는가. 충돌 · 이상은 상태가 아니라 Payment 의 충돌 기록으로 남김 (5주차 설계 2.2, D-65).
 * REQUESTED · UNKNOWN · PENDING 은 새 결제를 만들 근거가 아니며 조회로만 진행함
 */
public enum PaymentStatus {
    /** 발송 권한을 기록함. 생성 응답은 아직 반영하지 못함 */
    REQUESTED(false),
    /** 생성 결과를 확인하지 못함 (timeout · 연결 유실 · 해석 불가 응답) */
    UNKNOWN(false),
    /** 거래가 있고 PG 가 처리 중이라고 알려 줌 */
    PENDING(false),
    /** 해당 거래의 SUCCESS 를 조회로 확인함 */
    SUCCESS(true),
    /** 해당 거래의 FAILED 를 조회로 확인함 */
    FAILED(true),
    /** PG 가 거래를 만들지 않았다고 확정해 거절함 (효과 0건, D-67) */
    REJECTED(true);

    private final boolean terminal;

    PaymentStatus(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }
}
