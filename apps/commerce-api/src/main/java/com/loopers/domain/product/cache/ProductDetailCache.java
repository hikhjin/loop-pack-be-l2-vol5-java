package com.loopers.domain.product.cache;

import com.loopers.domain.product.vo.ProductView;
import java.util.Optional;
import java.util.function.Supplier;

/** 고객 상품 상세 캐시. 없는 상품도 "없음"으로 저장함 (4주차 detail-cache-design) */
public interface ProductDetailCache {

    /**
     * 버전을 읽은 뒤에 loader 를 부르므로 원본 읽기 트랜잭션은 loader 안에서 시작해야 함 (4).
     * 캐시 저장소 오류는 원본 결과로 대신하고, loader 의 오류는 그대로 나감
     */
    Optional<ProductView> get(Long productId, Supplier<Optional<ProductView>> loader);

    /** commit 뒤에 무효화함. 실패하면 나중에 다시 시도함 (3.5) */
    void invalidate(Long productId);
}
