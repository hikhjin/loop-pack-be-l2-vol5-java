package com.loopers.interfaces.api.like;

import com.loopers.application.like.LikeFacade;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.auth.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/products/{productId}/likes")
public class LikeV1Controller implements LikeV1ApiSpec {

    private final LikeFacade likeFacade;

    @PostMapping
    @Override
    public ApiResponse<LikeV1Dto.LikeResponse> like(LoginUser loginUser, @PathVariable Long productId) {
        return ApiResponse.success(LikeV1Dto.LikeResponse.from(likeFacade.like(loginUser.id(), productId)));
    }

    @DeleteMapping
    @Override
    public ApiResponse<LikeV1Dto.LikeResponse> unlike(LoginUser loginUser, @PathVariable Long productId) {
        return ApiResponse.success(LikeV1Dto.LikeResponse.from(likeFacade.unlike(loginUser.id(), productId)));
    }
}
