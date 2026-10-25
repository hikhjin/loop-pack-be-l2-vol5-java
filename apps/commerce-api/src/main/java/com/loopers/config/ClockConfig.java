package com.loopers.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/** 테스트에서 시각을 고정할 수 있도록 둠 (설계 2.3). 저장 직후 값과 다시 읽은 값이 같도록 DB 정밀도(마이크로초)로 자름 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return new MicrosecondClock(Clock.systemDefaultZone());
    }

    /** Clock.tick 은 1ms 미만 단위에서 millis() 가 0 으로 나눠 실패하므로 직접 자름 */
    static final class MicrosecondClock extends Clock {
        private final Clock base;

        MicrosecondClock(Clock base) {
            this.base = base;
        }

        @Override
        public ZoneId getZone() {
            return base.getZone();
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MicrosecondClock(base.withZone(zone));
        }

        @Override
        public Instant instant() {
            return base.instant().truncatedTo(ChronoUnit.MICROS);
        }
    }
}
