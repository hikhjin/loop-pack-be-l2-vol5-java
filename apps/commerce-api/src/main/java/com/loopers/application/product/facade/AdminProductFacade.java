package com.loopers.application.product.facade;

import com.loopers.application.product.dto.AdminProductInfo;
import com.loopers.domain.brand.entity.Brand;
import com.loopers.domain.brand.service.BrandService;
import com.loopers.domain.product.entity.Product;
import com.loopers.domain.product.cache.ProductDetailCache;
import com.loopers.domain.product.cache.ProductListCache;
import com.loopers.domain.product.service.ProductService;
import com.loopers.domain.product.vo.StockChange;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Component
public class AdminProductFacade {
    private final ProductService productService;
    private final BrandService brandService;
    private final ProductListCache productListCache;
    private final ProductDetailCache productDetailCache;

    /** 관리자 필터는 대상을 지정하므로 없는 브랜드를 알림 (D-24) */
    public Page<AdminProductInfo> getProducts(Long brandId, Pageable pageable) {
        if (brandId != null) {
            brandService.getActiveBrand(brandId);
        }
        return productService.getActiveProductsWithBrand(brandId, pageable).map(AdminProductInfo::from);
    }

    public AdminProductInfo getProduct(Long productId) {
        return AdminProductInfo.from(productService.getActiveProductWithBrand(productId));
    }

    /**
     * 등록 중 브랜드가 삭제되지 않도록 공유 잠금으로 읽음 (BRD-02, 3주차 설계 4.2).
     * 새 상품 ID 의 "없음"이 상세 캐시에 저장됐을 수 있어 상세도 무효화함 (4주차 detail-cache-design 3.3)
     */
    @Transactional
    public AdminProductInfo createProduct(Long brandId, String name, long price) {
        Brand brand = brandService.getActiveBrandForShare(brandId);
        Long productId = productService.create(brand, name, price).getId();
        productListCache.invalidate(brandId);
        productDetailCache.invalidate(productId);
        return getProduct(productId);
    }

    // 아래 쓰기는 ProductService 의 트랜잭션이 commit 된 뒤 무효화함 (4주차 cache-design 3.4)
    public AdminProductInfo updateProduct(Long productId, String name, long price) {
        Product product = productService.update(productId, name, price);
        productListCache.invalidate(product.getBrandId());
        productDetailCache.invalidate(productId);
        return getProduct(productId);
    }

    /** 고객 응답은 품절 여부만 내보내므로 그것이 바뀔 때만 무효화함 (4주차 cache-design 3.3) */
    public AdminProductInfo changeStock(Long productId, int stock) {
        StockChange change = productService.changeStock(productId, stock);
        if (change.soldOutChanged()) {
            productListCache.invalidate(change.product().getBrandId());
            productDetailCache.invalidate(productId);
        }
        return getProduct(productId);
    }

    public void deleteProduct(Long productId) {
        Product product = productService.delete(productId);
        productListCache.invalidate(product.getBrandId());
        productDetailCache.invalidate(productId);
    }
}
