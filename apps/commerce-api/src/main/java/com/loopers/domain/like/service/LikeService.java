package com.loopers.domain.like.service;

import com.loopers.domain.like.entity.Like;
import com.loopers.domain.like.repository.LikeRepository;
import com.loopers.domain.like.vo.LikedProduct;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Component
public class LikeService {

    private final LikeRepository likeRepository;

    /** 실제로 만들었으면 true (LIK-02). 동시 생성의 유니크 키 위반은 LikeRetrier 가 다시 실행함 (4주차 설계 3.2) */
    @Transactional
    public boolean like(Long userId, Long productId) {
        if (likeRepository.find(userId, productId).isPresent()) {
            return false;
        }
        likeRepository.save(new Like(userId, productId));
        return true;
    }

    /** 미리 읽지 않고 지운 행 수로 판단해 동시 취소에도 한 번만 true (LIK-03, 4주차 설계 3.2) */
    @Transactional
    public boolean unlike(Long userId, Long productId) {
        return likeRepository.delete(userId, productId) == 1;
    }

    @Transactional(readOnly = true)
    public Page<LikedProduct> getLikedProducts(Long userId, Pageable pageable) {
        return likeRepository.findLikedProducts(userId, pageable);
    }
}
