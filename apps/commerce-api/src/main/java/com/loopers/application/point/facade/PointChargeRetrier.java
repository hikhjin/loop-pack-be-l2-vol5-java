package com.loopers.application.point.facade;

import com.loopers.application.point.dto.PointInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

/** 버전 충돌만 새 트랜잭션으로 다시 실행하고 업무 거절은 다시 실행하지 않음. 시도마다 PointService 가 트랜잭션을 엶 (3주차 설계 4.4) */
@RequiredArgsConstructor
@Component
public class PointChargeRetrier {
    private final PointFacade pointFacade;

    @Retryable(
        retryFor = OptimisticLockingFailureException.class,
        maxAttempts = 3,
        backoff = @Backoff(delay = 50, multiplier = 2, maxDelay = 200, random = true)
    )
    public PointInfo charge(Long userId, long amount) {
        return pointFacade.charge(userId, amount);
    }
}
