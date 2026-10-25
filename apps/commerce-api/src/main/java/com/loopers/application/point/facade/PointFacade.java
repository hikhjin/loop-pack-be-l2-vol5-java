package com.loopers.application.point.facade;

import com.loopers.application.point.dto.PointInfo;
import com.loopers.domain.point.service.PointService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class PointFacade {
    private final PointService pointService;

    public PointInfo charge(Long userId, long amount) {
        return PointInfo.from(pointService.charge(userId, amount));
    }

    public PointInfo getPoint(Long userId) {
        return PointInfo.from(pointService.getPoint(userId));
    }
}
