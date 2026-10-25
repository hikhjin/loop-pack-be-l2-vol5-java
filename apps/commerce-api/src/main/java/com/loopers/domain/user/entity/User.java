package com.loopers.domain.user.entity;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** 식별자만 가진 실습용 고객 (설계 2.6). 테이블 이름은 MySQL 키워드를 피해 users */
@Entity
@Table(name = "users")
public class User extends BaseEntity {
}
