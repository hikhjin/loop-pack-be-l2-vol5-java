package com.loopers.domain.point.repository;

import com.loopers.domain.point.entity.Point;
import java.util.Optional;

public interface PointRepository {
    Point save(Point point);

    Optional<Point> findByUserId(Long userId);
}
