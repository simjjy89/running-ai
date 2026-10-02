package com.runningai.integration.garmin

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

/**
 * What the running application actually binds for the sample request size (Phase 6H-1C): full resolution
 * by default, overridable by the operator, and the same value the collection records as "requested".
 */
class GarminActivityDetailPropertiesTest {

    @Nested
    @SpringBootTest(
        properties = [
            "running-ai.intervals.api-key=",
            "running-ai.intervals.base-url=http://127.0.0.1:9",
        ],
    )
    @ActiveProfiles("test")
    inner class Default {

        @Autowired private lateinit var properties: GarminActivityDetailProperties

        @Test
        fun `nothing configured means full resolution, not the library default of 2000`() {
            assertThat(properties.samplesMaxChartSize).isEqualTo(20000)
            assertThat(properties.samplesMaxChartSize)
                .isEqualTo(GarminActivityDetailProperties.DEFAULT_SAMPLES_MAX_CHART_SIZE)
        }
    }

    @Nested
    @SpringBootTest(
        properties = [
            "running-ai.garmin.detail.samples-max-chart-size=3500",
            "running-ai.intervals.api-key=",
            "running-ai.intervals.base-url=http://127.0.0.1:9",
        ],
    )
    @ActiveProfiles("test")
    inner class Overridden {

        @Autowired private lateinit var properties: GarminActivityDetailProperties

        @Test
        fun `the operator can lower it, and the configured value is what gets used`() {
            assertThat(properties.samplesMaxChartSize).isEqualTo(3500)
        }
    }
}
