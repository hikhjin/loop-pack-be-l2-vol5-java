package com.loopers.support.pg;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * 모든 Spring 테스트의 PG 연결을 공유 테스트 PG 로 바꿈. src/test/resources/META-INF/spring.factories 로 등록함.
 * 컨텍스트를 만들기 전에 설정을 넣어, @ConfigurationProperties 바인딩보다 항상 앞서게 함.
 * 응답 대기는 timeout 장면이 테스트를 오래 붙잡지 않도록 짧게 둠
 */
public class TestPgContextInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("testPg", Map.of(
            "payment.test-pg.base-url", TestPgServer.shared().baseUrl(),
            "payment.test-pg.create-read-timeout", "1s",
            "payment.test-pg.lookup-read-timeout", "500ms"
        )));
    }
}
