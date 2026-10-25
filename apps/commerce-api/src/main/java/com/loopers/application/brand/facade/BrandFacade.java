package com.loopers.application.brand.facade;

import com.loopers.application.brand.dto.BrandInfo;
import com.loopers.domain.brand.service.BrandService;
import com.loopers.domain.product.cache.ProductListCache;
import com.loopers.domain.product.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Component
public class BrandFacade {
    private final BrandService brandService;
    private final ProductService productService;
    private final ProductListCache productListCache;

    public Page<BrandInfo> getBrands(Pageable pageable) {
        return brandService.getActiveBrands(pageable).map(BrandInfo::from);
    }

    public BrandInfo getBrand(Long brandId) {
        return BrandInfo.from(brandService.getActiveBrand(brandId));
    }

    public BrandInfo createBrand(String name, String description) {
        return BrandInfo.from(brandService.create(name, description));
    }

    /** 목록 응답에 브랜드 이름이 있어 commit 뒤 무효화함 (4주차 cache-design 3.3, 3.4) */
    public BrandInfo updateBrand(Long brandId, String name, String description) {
        BrandInfo updated = BrandInfo.from(brandService.update(brandId, name, description));
        productListCache.invalidate(brandId);
        return updated;
    }

    /** 브랜드와 상품 두 도메인을 한 트랜잭션으로 삭제하고, 캐시는 commit 뒤에만 무효화함 (BRD-02, 3주차 설계 2.2, 4주차 cache-design 3.4) */
    @Transactional
    public void deleteBrand(Long brandId) {
        brandService.delete(brandId);
        productService.deleteAllOfBrand(brandId);
        productListCache.invalidate(brandId);
    }
}
