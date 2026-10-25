package com.loopers.interfaces.api.point;

import com.loopers.application.point.dto.PointInfo;

public class PointV1Dto {
    /** 0 이하는 규칙의 주인인 Point 가 거절하도록 여기서 검사하지 않음 (D-25) */
    public record ChargeRequest(long amount) {}

    public record PointResponse(long balance) {
        public static PointResponse from(PointInfo info) {
            return new PointResponse(info.balance());
        }
    }
}
