package com.loopers.application.user.facade;

import com.loopers.domain.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class UserFacade {
    private final UserService userService;

    public boolean exists(Long userId) {
        return userService.exists(userId);
    }
}
