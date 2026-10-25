package com.loopers.application.point.dto;

import com.loopers.domain.point.entity.Point;

public record PointInfo(long balance) {
    public static PointInfo from(Point point) {
        return new PointInfo(point.getBalance());
    }
}
