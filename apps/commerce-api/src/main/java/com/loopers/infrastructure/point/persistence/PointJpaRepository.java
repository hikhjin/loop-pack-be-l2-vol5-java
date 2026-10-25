package com.loopers.infrastructure.point.persistence;

import com.loopers.domain.point.entity.Point;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PointJpaRepository extends JpaRepository<Point, Long> {
    Optional<Point> findByUserId(Long userId);
}
