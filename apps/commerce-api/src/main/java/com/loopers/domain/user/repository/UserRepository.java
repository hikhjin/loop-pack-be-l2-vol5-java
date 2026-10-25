package com.loopers.domain.user.repository;

import com.loopers.domain.user.entity.User;

public interface UserRepository {
    User save(User user);

    boolean exists(Long userId);

    long count();
}
