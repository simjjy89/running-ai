package com.runningai.training;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Fixed "now" of 2026-09-29T16:00:00Z, which is 2026-09-30 01:00 in Asia/Seoul: the UTC date (09-29)
 * and the athlete-local date (09-30) differ, so any test that reads "today" proves local-date handling.
 */
@TestConfiguration
class FixedClockTestConfig {

    @Bean
    @Primary
    Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-09-29T16:00:00Z"), ZoneOffset.UTC);
    }
}
