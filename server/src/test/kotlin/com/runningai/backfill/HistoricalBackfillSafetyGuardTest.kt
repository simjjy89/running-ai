package com.runningai.backfill

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.LocalDate

/**
 * §46: a historical backfill may not even start while any publishing or Garmin automation switch is
 * on. This context deliberately enables one switch (the legacy publish trigger alone is a valid
 * runtime, so the application still boots) and expects start AND resume to refuse before any work.
 */
@SpringBootTest(
    properties = [
        "running-ai.garmin.connector.base-url=http://127.0.0.1:9",
        "running-ai.intervals.api-key=",
        "running-ai.intervals.base-url=http://127.0.0.1:9",
        "running-ai.workout-publishing.enabled=true",
    ],
)
@ActiveProfiles("test")
class HistoricalBackfillSafetyGuardTest {

    @Autowired private lateinit var service: HistoricalBackfillService

    @Test
    fun `start refuses while a publishing switch is on`() {
        assertThatThrownBy { service.start(HistoricalBackfillRequest(LocalDate.parse("2026-10-03"), 90)) }
            .isInstanceOf(UnsafeBackfillRuntimeException::class.java)
            .hasMessageContaining("running-ai.workout-publishing.enabled")
    }
}
