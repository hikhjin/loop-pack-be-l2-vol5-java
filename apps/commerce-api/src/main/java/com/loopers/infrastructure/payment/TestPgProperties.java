package com.loopers.infrastructure.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 로컬 테스트 PG 연결 설정. base URL · callback URL 은 설정으로 고정하고 고객 입력으로 바꾸지 않음 (5주차 설계 3.1).
 * 응답 대기는 create 와 조회를 따로 둠 (5주차 설계 5)
 */
@ConfigurationProperties("payment.test-pg")
public record TestPgProperties(
    String baseUrl,
    String callbackUrl,
    @DefaultValue("135135") String providerUserId,
    @DefaultValue("1s") Duration connectTimeout,
    @DefaultValue("3s") Duration createReadTimeout,
    @DefaultValue("1s") Duration lookupReadTimeout
) {}
