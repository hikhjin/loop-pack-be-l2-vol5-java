package com.loopers.domain.point.entity;

import com.loopers.domain.point.enums.PointErrorCode;
import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

/** 사용자 한 명의 포인트 잔액. 1포인트 = 1원 (설계 2.3) */
@Entity
@Table(name = "points", uniqueConstraints = @UniqueConstraint(name = "uk_points_user", columnNames = "user_id"))
public class Point extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    private long balance;

    /** 충전 · 결제가 겹치면 나중 commit 이 충돌하고 ~Retrier 가 다시 실행함 (3주차 설계 4.1) */
    @Version
    private Long version;

    protected Point() {}

    public Point(Long userId) {
        this.userId = userId;
        this.balance = 0L;
    }

    public Long getUserId() {
        return userId;
    }

    public long getBalance() {
        return balance;
    }

    /** 합계가 저장 타입 범위를 넘어도 거절함 (PNT-01, PNT-02) */
    public void charge(long amount) {
        if (amount <= 0) {
            throw new CoreException(PointErrorCode.INVALID_CHARGE_AMOUNT);
        }
        try {
            this.balance = Math.addExact(balance, amount);
        } catch (ArithmeticException e) {
            throw new CoreException(PointErrorCode.BALANCE_LIMIT_EXCEEDED);
        }
    }

    /** 0원 결제는 허용함 (D-30, PNT-03) */
    public void pay(long amount) {
        if (amount < 0 || amount > balance) {
            throw new CoreException(PointErrorCode.INSUFFICIENT_POINT);
        }
        this.balance -= amount;
    }
}
