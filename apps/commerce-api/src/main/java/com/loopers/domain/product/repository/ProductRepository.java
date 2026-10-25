package com.loopers.domain.product.repository;

import com.loopers.domain.product.entity.Product;
import com.loopers.domain.product.enums.ProductSort;
import com.loopers.domain.product.vo.ProductView;
import com.loopers.domain.product.vo.ProductWithBrand;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface ProductRepository {
    Product save(Product product);

    Optional<Product> findActive(Long productId);

    /** product 행을 쓰는 경로가 씀 (3주차 설계 4.2) */
    Optional<Product> findActiveForUpdate(Long productId);

    /** 여러 상품을 잠그는 경로가 같은 순서(식별자 오름차순)로 잠가 교착을 피함 (3주차 설계 4.3) */
    List<Product> findAllActiveForUpdate(Collection<Long> productIds);

    /** 삭제 여부와 관계없이 식별자 오름차순으로 잠금. 확보 뒤 삭제된 상품의 예약 반환에 씀 */
    List<Product> findAllForUpdate(Collection<Long> productIds);

    Set<Long> findActiveIds(Collection<Long> productIds);

    Optional<ProductWithBrand> findActiveWithBrand(Long productId);

    /** 생성 시각 desc, 식별자 desc. brandId 가 null 이면 거르지 않음 */
    Page<ProductWithBrand> findActiveWithBrand(Long brandId, Pageable pageable);

    Optional<ProductView> findActiveView(Long productId);

    /** brandId 가 null 이면 거르지 않고, 없는 브랜드면 빈 결과 */
    Page<ProductView> findActiveViews(Long brandId, ProductSort sort, Pageable pageable);

    /** 엔티티를 거치지 않는 삭제라 시각을 직접 받음 (3주차 설계 2.3) */
    int deleteAllOfBrand(Long brandId, ZonedDateTime deletedAt);
}
