package com.loopers.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.TimeZone;

/** 공용 모듈(supports:jackson)은 다른 앱에도 영향을 주므로 commerce-api 에만 적용함 */
@Configuration
public class CommerceApiJacksonConfig {

    public static final String RESPONSE_TIME_ZONE = "Asia/Seoul";

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer commerceApiJacksonCustomizer() {
        return builder -> builder
            // 소수점을 버려 받지 않고 400 으로 거절함 (D-37)
            .featuresToDisable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            // 방금 만든 엔티티와 DB 에서 읽은 엔티티의 시각 표기가 갈리지 않도록 한 시간대로 내보냄 (D-40)
            .timeZone(TimeZone.getTimeZone(RESPONSE_TIME_ZONE));
    }
}
