package com.loopers.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 정수 필드에 소수를 보내면 소수점을 버려 받지 않고 형식 오류(400)로 거절한다.
 * 충전액 · 가격 · 재고 · 수량이 요청과 다른 값으로 조용히 바뀌지 않게 한다.
 * 공용 모듈(supports:jackson)은 바꾸지 않고 commerce-api 에만 적용한다.
 */
@Configuration
public class StrictNumberJacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer strictNumberCustomizer() {
        return builder -> builder.featuresToDisable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    }
}
