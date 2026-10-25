package com.loopers.domain.product.cache;

import com.loopers.domain.product.vo.ProductView;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/** DB · Redis 없이 무효화 호출을 확인하기 위한 캐시. 항상 원본을 읽고, 무효화한 상품을 순서대로 기록한다 */
public class FakeProductDetailCache implements ProductDetailCache {

    private final List<Long> invalidatedProductIds = new ArrayList<>();

    @Override
    public Optional<ProductView> get(Long productId, Supplier<Optional<ProductView>> loader) {
        return loader.get();
    }

    @Override
    public void invalidate(Long productId) {
        invalidatedProductIds.add(productId);
    }

    public List<Long> invalidatedProductIds() {
        return List.copyOf(invalidatedProductIds);
    }
}
