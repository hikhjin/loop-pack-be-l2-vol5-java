package com.loopers.domain.product.cache;

import com.loopers.domain.product.enums.ProductSort;
import com.loopers.domain.product.vo.ProductView;
import org.springframework.data.domain.Page;

import java.util.function.Supplier;

/** 고객 상품 목록 첫 페이지 캐시 (4주차 cache-design) */
public interface ProductListCache {

    /**
     * 버전을 읽은 뒤에 loader 를 부르므로 원본 읽기 트랜잭션은 loader 안에서 시작해야 함 (4.1).
     * 캐시 저장소 오류는 원본 결과로 대신하고, loader 의 오류는 그대로 나감 (4.2)
     *
     * @param brandId null 이면 전체 목록
     */
    Page<ProductView> getFirstPage(Long brandId, ProductSort sort, Supplier<Page<ProductView>> loader);

    /** 브랜드 목록과 전체 목록을 commit 뒤에 무효화함. 실패하면 나중에 다시 시도함 (3.4) */
    void invalidate(Long brandId);
}
