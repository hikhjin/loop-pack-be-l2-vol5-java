package com.loopers.interfaces.api.like;

import com.loopers.application.like.LikeInfo;

public class LikeV1Dto {
    public record LikeResponse(Long productId, long likeCount) {
        public static LikeResponse from(LikeInfo info) {
            return new LikeResponse(info.productId(), info.likeCount());
        }
    }
}
