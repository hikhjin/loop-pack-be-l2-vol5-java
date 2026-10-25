package com.loopers.domain.brand.repository;

import com.loopers.domain.brand.entity.Brand;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

public interface BrandRepository {
    Brand save(Brand brand);

    Optional<Brand> findActive(Long brandId);

    /** 생성 시각 desc, 식별자 desc */
    Page<Brand> findActive(Pageable pageable);

    /** 배타 잠금. 브랜드를 바꾸는 수정 · 삭제가 씀 (3주차 설계 4.2) */
    Optional<Brand> findActiveForUpdate(Long brandId);

    /** 공유 잠금. 브랜드에 기대기만 하는 상품 등록이 씀 (3주차 설계 4.2) */
    Optional<Brand> findActiveForShare(Long brandId);
}
