package com.runningai.integration.garmin

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Reading the counts a Garmin sample payload reports about itself (Phase 6H-1C). Nothing here repairs or
 * infers a count: a value that cannot be read as a non-negative integer is reported as absent, which makes
 * the caller classify the stream UNKNOWN instead of trusting it.
 */
class GarminSampleMetadataMapperTest {

    private val json = ObjectMapper()
    private val mapper = GarminSampleMetadataMapper()

    private fun read(text: String) = mapper.read(json.readTree(text))

    @Test
    fun `the counts are taken from the payload exactly as reported`() {
        val counts = read(
            """{"metricsCount":1399,"totalMetricsCount":2784,
               "activityDetailMetrics":[{"metrics":[1.0]},{"metrics":[2.0]}]}""",
        )

        assertThat(counts.metricsCount).isEqualTo(1399)
        assertThat(counts.totalMetricsCount).isEqualTo(2784)
        assertThat(counts.payloadSampleCount).isEqualTo(2)
    }

    @Test
    fun `a missing count stays missing`() {
        val counts = read("""{"metricsCount":5,"activityDetailMetrics":[]}""")

        assertThat(counts.metricsCount).isEqualTo(5)
        assertThat(counts.totalMetricsCount).isNull()
        assertThat(counts.payloadSampleCount).isZero()
    }

    @Test
    fun `a count that is not a non-negative integer is treated as absent`() {
        listOf(
            """{"totalMetricsCount":"2784"}""",
            """{"totalMetricsCount":2784.5}""",
            """{"totalMetricsCount":-3}""",
            """{"totalMetricsCount":null}""",
        ).forEach { assertThat(read(it).totalMetricsCount).isNull() }
    }

    @Test
    fun `a payload that is not an object reports nothing rather than failing`() {
        // the sample mapper is what rejects an unusable shape; this one only reads counts
        assertThat(read("[]").payloadSampleCount).isZero()
        assertThat(read("null").totalMetricsCount).isNull()
    }
}
