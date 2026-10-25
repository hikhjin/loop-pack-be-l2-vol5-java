package com.loopers.application.product.facade;

import com.loopers.application.product.dto.ProductInfo;
import com.loopers.domain.product.cache.ProductDetailCache;
import com.loopers.domain.product.enums.ProductErrorCode;
import com.loopers.domain.product.cache.ProductListCache;
import com.loopers.domain.product.service.ProductService;
import com.loopers.domain.product.enums.ProductSort;
import com.loopers.domain.product.vo.ProductView;
import com.loopers.support.error.CoreException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class ProductFacade {

    /** 외부 계약의 기본 크기 (4주차 cache-design 2.1) */
    static final int CACHEABLE_SIZE = 20;

    private final ProductService productService;
    private final ProductListCache productListCache;
    private final ProductDetailCache productDetailCache;

    /** 캐시가 버전을 읽은 뒤에 원본 읽기 트랜잭션이 시작되도록 트랜잭션을 열지 않음. 없는 브랜드면 빈 목록 (D-24, 4주차 cache-design 4.1) */
    public Page<ProductInfo> getProducts(Long brandId, ProductSort sort, Pageable pageable) {
        Page<ProductView> views = isCacheable(pageable)
            ? productListCache.getFirstPage(brandId, sort, () -> productService.getActiveProductViews(brandId, sort, pageable))
            : productService.getActiveProductViews(brandId, sort, pageable);
        return views.map(ProductInfo::from);
    }

    /** 캐시가 "없음"도 저장하므로 404 를 여기서 정하고, 트랜잭션은 열지 않음 (4주차 detail-cache-design 3.4, 4) */
    public ProductInfo getProduct(Long productId) {
        ProductView view = productDetailCache.get(productId, () -> productService.findActiveProductView(productId))
            .orElseThrow(() -> new CoreException(ProductErrorCode.PRODUCT_NOT_FOUND));
        return ProductInfo.from(view);
    }

    private static boolean isCacheable(Pageable pageable) {
        return pageable.getPageNumber() == 0 && pageable.getPageSize() == CACHEABLE_SIZE;
    }
}
