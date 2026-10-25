package com.loopers.domain.point.service;

import com.loopers.domain.point.entity.Point;
import com.loopers.domain.point.repository.PointRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Component
public class PointService {

    private final PointRepository pointRepository;

    /** "행 없음"을 0원으로 바꾸는 곳은 여기 하나이며, 조회만으로는 행을 만들지 않음 (설계 2.3) */
    @Transactional(readOnly = true)
    public Point getPoint(Long userId) {
        return pointRepository.findByUserId(userId).orElseGet(() -> new Point(userId));
    }

    /** 행은 첫 충전 때 생김 */
    @Transactional
    public Point charge(Long userId, long amount) {
        Point point = getPoint(userId);
        point.charge(amount);
        return pointRepository.save(point);
    }
}
