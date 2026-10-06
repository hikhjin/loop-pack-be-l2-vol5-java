package com.loopers.application.order;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.brand.FakeBrandRepository;
import com.loopers.domain.order.FakeOrderRepository;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.order.OrderService.OrderRequestLine;
import com.loopers.domain.point.FakePointRepository;
import com.loopers.domain.point.PointService;
import com.loopers.domain.product.FakeProductRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** 판매 여부는 Facade 가 조합하는 응답 값이라 DB 없이 fake 저장 구현으로 확인함 (설계 6.4, D-42) */
class OrderFacadeTest {

    private static final Long USER_ID = 1L;

    private FakeProductRepository productRepository;
    private OrderFacade orderFacade;
    private Brand brand;

    @BeforeEach
    void setUp() {
        FakeBrandRepository brandRepository = new FakeBrandRepository();
        productRepository = new FakeProductRepository();
        ProductService productService = new ProductService(productRepository, new BrandService(brandRepository));
        OrderService orderService = new OrderService(
            new FakeOrderRepository(), productService, new PointService(new FakePointRepository()), Clock.systemDefaultZone());
        orderFacade = new OrderFacade(orderService, productService);
        brand = brandRepository.save(new Brand("브랜드", null));
    }

    private Product saveProduct(String name) {
        return productRepository.save(new Product(brand, name, 1_000L));
    }

    @DisplayName("내 주문 상세를 조회할 때, ")
    @Nested
    class GetMyOrder {
        @DisplayName("주문한 상품이 이후 삭제되었으면, 그 품목은 판매 여부가 false 이고 나머지는 true 다.")
        @Test
        void marksDeletedProductAsNotOnSale() {
            // arrange
            Product active = saveProduct("판매 중");
            Product deleted = saveProduct("판매 종료");
            OrderInfo created = orderFacade.createOrder(USER_ID, List.of(
                new OrderRequestLine(active.getId(), 1),
                new OrderRequestLine(deleted.getId(), 1)
            ));
            deleted.delete();

            // act
            OrderDetailInfo result = orderFacade.getMyOrder(USER_ID, created.id());

            // assert
            assertThat(result.items())
                .extracting(OrderDetailInfo.Item::productName, OrderDetailInfo.Item::onSale)
                .containsExactly(tuple("판매 중", true), tuple("판매 종료", false));
        }
    }
}
