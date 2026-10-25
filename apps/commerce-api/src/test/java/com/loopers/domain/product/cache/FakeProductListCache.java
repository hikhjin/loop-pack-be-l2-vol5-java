package com.loopers.domain.product.cache;

import com.loopers.domain.product.enums.ProductSort;
import com.loopers.domain.product.vo.ProductView;
import org.springframework.data.domain.Page;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** 항상 원본을 읽고, 무효화한 브랜드를 순서대로 기록함 */
public class FakeProductListCache implements ProductListCache {

    private final List<Long> invalidatedBrandIds = new ArrayList<>();

    @Override
    public Page<ProductView> getFirstPage(Long brandId, ProductSort sort, Supplier<Page<ProductView>> loader) {
        return loader.get();
    }

    @Override
    public void invalidate(Long brandId) {
        invalidatedBrandIds.add(brandId);
    }

    public List<Long> invalidatedBrandIds() {
        return List.copyOf(invalidatedBrandIds);
    }
}
