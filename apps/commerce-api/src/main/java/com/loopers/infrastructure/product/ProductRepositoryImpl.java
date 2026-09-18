package com.loopers.infrastructure.product;

import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.ProductWithBrand;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.Optional;

@RequiredArgsConstructor
@Component
public class ProductRepositoryImpl implements ProductRepository {
    private final ProductJpaRepository productJpaRepository;

    @Override
    public Product save(Product product) {
        return productJpaRepository.save(product);
    }

    @Override
    public Optional<Product> findActive(Long productId) {
        return productJpaRepository.findByIdAndDeletedAtIsNull(productId);
    }

    @Override
    public Optional<ProductWithBrand> findActiveWithBrand(Long productId) {
        return productJpaRepository.findActiveWithBrand(productId);
    }

    @Override
    public Page<ProductWithBrand> findActiveWithBrand(Long brandId, Pageable pageable) {
        return productJpaRepository.findActiveWithBrand(brandId, pageable);
    }
}
