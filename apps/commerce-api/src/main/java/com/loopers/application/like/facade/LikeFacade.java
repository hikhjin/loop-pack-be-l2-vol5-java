package com.loopers.application.like.facade;

import com.loopers.application.like.dto.LikeInfo;
import com.loopers.application.like.dto.LikedProductInfo;
import com.loopers.domain.like.service.LikeService;
import com.loopers.domain.product.service.ProductService;
import com.loopers.domain.user.enums.UserErrorCode;
import com.loopers.support.error.CoreException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@RequiredArgsConstructor
@Component
public class LikeFacade {
    private final LikeService likeService;
    private final ProductService productService;

    /** 관계가 실제로 생겼을 때만 좋아요 수를 늘리고, 그 행의 잠금이 짧도록 마지막에 씀 (LIK-01, 4주차 설계 3.2) */
    @Transactional
    public LikeInfo like(Long userId, Long productId) {
        productService.getActiveProduct(productId);
        if (likeService.like(userId, productId)) {
            productService.increaseLikeCount(productId);
        }
        return new LikeInfo(productId, productService.getLikeCount(productId));
    }

    /** 타인의 존재를 드러내지 않도록 본인이 아니면 없는 대상으로 응답함 (설계 6.1) */
    public Page<LikedProductInfo> getMyLikes(Long requesterId, Long userId, Pageable pageable) {
        if (!Objects.equals(requesterId, userId)) {
            throw new CoreException(UserErrorCode.USER_NOT_FOUND);
        }
        return likeService.getLikedProducts(userId, pageable).map(LikedProductInfo::from);
    }

    /** 관계를 실제로 지웠을 때만 좋아요 수를 줄임. 삭제된 상품도 같음 (LIK-03, 4주차 설계 3.2) */
    @Transactional
    public LikeInfo unlike(Long userId, Long productId) {
        if (likeService.unlike(userId, productId)) {
            productService.decreaseLikeCount(productId);
        }
        return new LikeInfo(productId, productService.getLikeCount(productId));
    }
}
