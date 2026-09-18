package com.loopers.interfaces.api.order;

import com.loopers.application.order.OrderFacade;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.auth.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/orders")
public class OrderV1Controller implements OrderV1ApiSpec {

    private final OrderFacade orderFacade;

    @PostMapping
    @Override
    public ApiResponse<OrderV1Dto.OrderResponse> createOrder(LoginUser loginUser, @RequestBody OrderV1Dto.CreateRequest request) {
        return ApiResponse.success(OrderV1Dto.OrderResponse.from(orderFacade.createOrder(loginUser.id(), request.toLines())));
    }

    @PostMapping("/{orderId}/confirm")
    @Override
    public ApiResponse<OrderV1Dto.OrderResponse> confirmOrder(LoginUser loginUser, @PathVariable Long orderId) {
        return ApiResponse.success(OrderV1Dto.OrderResponse.from(orderFacade.confirmOrder(loginUser.id(), orderId)));
    }
}
