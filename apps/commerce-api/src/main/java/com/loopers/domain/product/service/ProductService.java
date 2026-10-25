package com.loopers.domain.product.service;

import com.loopers.domain.product.entity.Product;
import com.loopers.domain.product.entity.ProductLikeCount;
import com.loopers.domain.product.enums.ProductErrorCode;
import com.loopers.domain.product.enums.ProductSort;
import com.loopers.domain.product.repository.ProductLikeCountRepository;
import com.loopers.domain.product.repository.ProductRepository;
import com.loopers.domain.product.vo.ProductErrorDetail;
import com.loopers.domain.product.vo.ProductView;
import com.loopers.domain.product.vo.ProductWithBrand;
import com.loopers.domain.product.vo.StockChange;
import com.loopers.domain.brand.entity.Brand;
import com.loopers.support.error.CoreException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Component
public class ProductService {

    private final ProductRepository productRepository;
    private final ProductLikeCountRepository productLikeCountRepository;
    /** 엔티티를 거치지 않는 일괄 삭제라 시각을 여기서 정함 (3주차 설계 2.3) */
    private final Clock clock;

    /** 다른 도메인도 이 조회를 씀 (D-31, 설계 6.4) */
    @Transactional(readOnly = true)
    public Product getActiveProduct(Long productId) {
        return productRepository.findActive(productId)
            .orElseThrow(() -> new CoreException(ProductErrorCode.PRODUCT_NOT_FOUND, null, ProductErrorDetail.of(productId)));
    }

    /** 교착을 피하려 식별자 순으로 잠그되, 없는 상품은 품목 순서의 첫 번째로 알림 (ORD-09, D-16, 3주차 설계 4.3) */
    @Transactional
    public Map<Long, Product> getActiveProductsForUpdate(List<Long> productIds) {
        Map<Long, Product> locked = productRepository.findAllActiveForUpdate(productIds).stream()
            .collect(Collectors.toMap(Product::getId, Function.identity()));
        Map<Long, Product> products = new LinkedHashMap<>();
        for (Long productId : productIds) {
            Product product = locked.get(productId);
            if (product == null) {
                throw new CoreException(ProductErrorCode.PRODUCT_NOT_FOUND, null, ProductErrorDetail.of(productId));
            }
            products.put(productId, product);
        }
        return products;
    }

    /** 예약 반환용. 상품은 논리 삭제라 행이 없으면 예약과 어긋난 것이므로 상태 오류로 막음 */
    @Transactional
    public Map<Long, Product> getProductsForUpdate(List<Long> productIds) {
        Map<Long, Product> locked = productRepository.findAllForUpdate(productIds).stream()
            .collect(Collectors.toMap(Product::getId, Function.identity()));
        Map<Long, Product> products = new LinkedHashMap<>();
        for (Long productId : productIds) {
            Product product = locked.get(productId);
            if (product == null) {
                throw new IllegalStateException("예약한 상품이 없음: " + productId);
            }
            products.put(productId, product);
        }
        return products;
    }

    private Product getActiveProductForUpdate(Long productId) {
        return productRepository.findActiveForUpdate(productId)
            .orElseThrow(() -> new CoreException(ProductErrorCode.PRODUCT_NOT_FOUND, null, ProductErrorDetail.of(productId)));
    }

    /** 판매 여부처럼 삭제 여부만 필요한 조회용이라 예외 없이 뺌 (설계 6.4, D-42) */
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

    /** 상세 캐시가 "없음"을 값으로 저장하므로 오류는 호출자가 정함 (4주차 detail-cache-design 3.4) */
    @Transactional(readOnly = true)
    public Optional<ProductView> findActiveProductView(Long productId) {
        return productRepository.findActiveView(productId);
    }

    @Transactional(readOnly = true)
    public Page<ProductView> getActiveProductViews(Long brandId, ProductSort sort, Pageable pageable) {
        return productRepository.findActiveViews(brandId, sort, pageable);
    }

    /** 모든 상품이 좋아요 수 행을 갖도록 0 으로 함께 만듦 (BRD-02, 4주차 설계 2.2) */
    @Transactional
    public Product create(Brand brand, String name, long price) {
        Product product = productRepository.save(new Product(brand, name, price));
        productLikeCountRepository.save(new ProductLikeCount(product.getId()));
        return product;
    }

    /** 변경 감지는 모든 컬럼을 다시 쓰므로 잠금 읽기로 그 사이의 차감을 덮어쓰지 않음 (3주차 설계 4.2) */
    @Transactional
    public Product update(Long productId, String name, long price) {
        Product product = getActiveProductForUpdate(productId);
        product.update(name, price);
        return product;
    }

    /** 잠금 안에서 바꾸기 전과 비교해 품절 여부 변화를 함께 돌려줌 (4주차 cache-design 3.3) */
    @Transactional
    public StockChange changeStock(Long productId, int stock) {
        Product product = getActiveProductForUpdate(productId);
        boolean wasSoldOut = product.isSoldOut();
        product.changeStock(stock);
        return new StockChange(product, wasSoldOut != product.isSoldOut());
    }

    /** 캐시 무효화 범위를 정하도록 삭제한 상품을 돌려줌 (4주차 cache-design 3.3) */
    @Transactional
    public Product delete(Long productId) {
        Product product = getActiveProductForUpdate(productId);
        product.delete();
        return product;
    }

    /** 모든 상품은 좋아요 수 행을 가지므로 바뀐 행이 없으면 불변식이 깨진 것임 (4주차 설계 3.2) */
    @Transactional
    public void increaseLikeCount(Long productId) {
        requireLikeCountRow(productLikeCountRepository.increase(productId, ZonedDateTime.now(clock)), productId);
    }

    @Transactional
    public void decreaseLikeCount(Long productId) {
        requireLikeCountRow(productLikeCountRepository.decrease(productId, ZonedDateTime.now(clock)), productId);
    }

    /** 없는 상품의 취소도 0 으로 응답하도록 없는 상품은 0 (LIK-03) */
    @Transactional(readOnly = true)
    public long getLikeCount(Long productId) {
        return productLikeCountRepository.findLikeCount(productId).orElse(0L);
    }

    private static void requireLikeCountRow(int updatedRows, Long productId) {
        if (updatedRows != 1) {
            throw new IllegalStateException("좋아요 수 행이 없는 상품: " + productId);
        }
    }

    /** 재고 0 인 상품도 함께 삭제함 (BRD-02, 3주차 설계 2.3) */
    @Transactional
    public int deleteAllOfBrand(Long brandId) {
        return productRepository.deleteAllOfBrand(brandId, ZonedDateTime.now(clock));
    }
}
