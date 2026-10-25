package com.loopers.domain.brand.repository;

import com.loopers.domain.brand.entity.Brand;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class FakeBrandRepository implements BrandRepository {

    private final Map<Long, Brand> brands = new LinkedHashMap<>();
    private long sequence = 0L;

    @Override
    public Brand save(Brand brand) {
        if (brand.getId() == null || brand.getId() == 0L) {
            ReflectionTestUtils.setField(brand, "id", ++sequence);
        }
        brands.put(brand.getId(), brand);
        return brand;
    }

    @Override
    public Optional<Brand> findActive(Long brandId) {
        return Optional.ofNullable(brands.get(brandId)).filter(brand -> !brand.isDeleted());
    }

    // 잠금은 실제 DB 의 동시성 테스트가 확인함
    @Override
    public Optional<Brand> findActiveForUpdate(Long brandId) {
        return findActive(brandId);
    }

    @Override
    public Optional<Brand> findActiveForShare(Long brandId) {
        return findActive(brandId);
    }

    @Override
    public Page<Brand> findActive(Pageable pageable) {
        List<Brand> active = brands.values().stream().filter(brand -> !brand.isDeleted()).toList();
        return new PageImpl<>(active, pageable, active.size());
    }
}
