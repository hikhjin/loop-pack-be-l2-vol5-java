package com.loopers.application.like.facade;

import com.loopers.application.like.dto.LikeInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

/** 동시 등록의 유니크 키 위반을 새 트랜잭션으로 다시 실행하면 앞선 관계가 보여 멱등하게 성공함 (LIK-02, 4주차 설계 3.3) */
@RequiredArgsConstructor
@Component
public class LikeRetrier {
    private final LikeFacade likeFacade;

    @Retryable(
        retryFor = DuplicateKeyException.class,
        maxAttempts = 3,
        backoff = @Backoff(delay = 50, multiplier = 2, maxDelay = 200, random = true)
    )
    public LikeInfo like(Long userId, Long productId) {
        return likeFacade.like(userId, productId);
    }
}
