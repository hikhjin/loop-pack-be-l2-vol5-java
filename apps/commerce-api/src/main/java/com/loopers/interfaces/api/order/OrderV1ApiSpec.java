package com.loopers.interfaces.api.order;

import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.auth.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Order V1 API", description = "주문 API 입니다. X-USER-ID 헤더로 요청자를 식별합니다.")
public interface OrderV1ApiSpec {

    @Operation(
        summary = "주문 생성",
        description = "DRAFT 로 저장하며 재고 · 포인트는 차감하지 않습니다. 같은 상품의 품목은 합산하므로 응답의 품목 수가 요청과 다를 수 있습니다."
    )
    ApiResponse<OrderV1Dto.OrderResponse> createOrder(@Parameter(hidden = true) LoginUser loginUser, OrderV1Dto.CreateRequest request);

    @Operation(
        summary = "주문 확정",
        description = "재고 · 포인트를 차감하고 CONFIRMED 로 바꿉니다. 상품 때문에 실패하면 data.productId 로 처음 실패한 상품을 알립니다."
    )
    ApiResponse<OrderV1Dto.OrderResponse> confirmOrder(
        @Parameter(hidden = true) LoginUser loginUser,
        @Schema(description = "주문 ID") Long orderId
    );
}
