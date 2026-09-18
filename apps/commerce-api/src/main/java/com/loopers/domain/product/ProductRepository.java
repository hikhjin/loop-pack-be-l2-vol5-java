package com.loopers.domain.product;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

public interface ProductRepository {
    Product save(Product product);

    Optional<Product> findActive(Long productId);

    Optional<ProductWithBrand> findActiveWithBrand(Long productId);

    /** 삭제되지 않은 상품을 생성 시각 desc, 식별자 desc 로 조회한다. brandId 가 null 이면 브랜드로 거르지 않는다. */
    Page<ProductWithBrand> findActiveWithBrand(Long brandId, Pageable pageable);
}
