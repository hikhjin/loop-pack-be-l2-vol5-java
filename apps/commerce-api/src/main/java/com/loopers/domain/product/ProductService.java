package com.loopers.domain.product;

import com.loopers.domain.brand.Brand;
import com.loopers.support.error.CoreException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.Set;

@RequiredArgsConstructor
@Component
public class ProductService {

    private final ProductRepository productRepository;
    /** 일괄 삭제 시각의 기준. 엔티티를 거치지 않는 삭제라 시각을 여기서 정함 (3주차 설계 2.3) */
    private final Clock clock;

    /**
     * 없거나 삭제된 상품은 PRODUCT_NOT_FOUND. 다른 도메인도 이 조회를 쓴다 (설계 D-31).
     * 여러 품목 중 어느 상품인지 알 수 있도록 상품 식별자를 부가 정보로 담는다 (설계 6.4).
     */
    @Transactional(readOnly = true)
    public Product getActiveProduct(Long productId) {
        return productRepository.findActive(productId)
            .orElseThrow(() -> new CoreException(ProductErrorCode.PRODUCT_NOT_FOUND, null, ProductErrorDetail.of(productId)));
    }

    /**
     * 주어진 식별자 중 살아 있는 상품의 식별자. 대상 없음 예외를 던지지 않음.
     * 주문 상세의 판매 여부처럼 삭제 여부만 알면 되는 조회가 쓰며, 삭제 조건은 다른 살아 있는 상품 조회와 같음 (설계 6.4, D-42)
     */
    @Transactional(readOnly = true)
    public Set<Long> getActiveProductIds(Collection<Long> productIds) {
        if (productIds.isEmpty()) {
            return Set.of();
        }
        return productRepository.findActiveIds(productIds);
    }

    @Transactional(readOnly = true)
    public ProductWithBrand getActiveProductWithBrand(Long productId) {
        return productRepository.findActiveWithBrand(productId)
            .orElseThrow(() -> new CoreException(ProductErrorCode.PRODUCT_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public Page<ProductWithBrand> getActiveProductsWithBrand(Long brandId, Pageable pageable) {
        return productRepository.findActiveWithBrand(brandId, pageable);
    }

    @Transactional(readOnly = true)
    public ProductView getActiveProductView(Long productId) {
        return productRepository.findActiveView(productId)
            .orElseThrow(() -> new CoreException(ProductErrorCode.PRODUCT_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public Page<ProductView> getActiveProductViews(Long brandId, ProductSort sort, Pageable pageable) {
        return productRepository.findActiveViews(brandId, sort, pageable);
    }

    /** 살아 있는 브랜드 조회는 조율하는 Facade 가 하고, 생성 입구의 확인은 Product 가 한 번 더 함 (BRD-02, 설계 4.4) */
    @Transactional
    public Product create(Brand brand, String name, long price) {
        return productRepository.save(new Product(brand, name, price));
    }

    @Transactional
    public Product update(Long productId, String name, long price) {
        Product product = getActiveProduct(productId);
        product.update(name, price);
        return product;
    }

    @Transactional
    public Product changeStock(Long productId, int stock) {
        Product product = getActiveProduct(productId);
        product.changeStock(stock);
        return product;
    }

    @Transactional
    public void delete(Long productId) {
        getActiveProduct(productId).delete();
    }

    /** 브랜드의 삭제되지 않은 상품(재고 0 포함)을 모두 삭제하고 삭제한 수를 돌려줌. 대상이 없으면 0 (BRD-02, 3주차 설계 2.3) */
    @Transactional
    public int deleteAllOfBrand(Long brandId) {
        return productRepository.deleteAllOfBrand(brandId, ZonedDateTime.now(clock));
    }
}
