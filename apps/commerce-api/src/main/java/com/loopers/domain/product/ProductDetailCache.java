package com.loopers.domain.product;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 고객 상품 상세의 캐시 (4주차 docs/week4/detail-cache-design.md).
 * 상품마다 버전을 두고, 무효화는 버전을 올려 옛 키를 버린다. 없는 상품 · 삭제된 상품도 "없음"으로 저장한다.
 */
public interface ProductDetailCache {

    /**
     * 상품 버전을 먼저 읽고, 그 버전의 캐시가 있으면 돌려주고 없으면 loader 로 원본을 읽어 그 버전에 저장한다.
     * 원본이 비어 있으면("없음") 그것도 저장한다 (3.3).
     * loader 는 버전을 읽은 뒤에 부르므로 원본 읽기 트랜잭션은 loader 안에서 시작해야 한다 (4).
     * 캐시 저장소 오류는 원본 결과로 대신하며, loader 의 오류는 그대로 나간다.
     */
    Optional<ProductView> get(Long productId, Supplier<Optional<ProductView>> loader);

    /**
     * 그 상품의 상세를 무효화한다. 진행 중인 트랜잭션이 있으면 commit 뒤에 하고, rollback 되면 하지 않는다.
     * 무효화 실패는 쓰기 결과에 영향을 주지 않고 나중에 다시 시도한다 (3.5).
     */
    void invalidate(Long productId);
}
