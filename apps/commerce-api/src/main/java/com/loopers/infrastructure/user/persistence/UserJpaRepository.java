package com.loopers.infrastructure.user.persistence;

import com.loopers.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserJpaRepository extends JpaRepository<User, Long> {}
