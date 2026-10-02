package com.runningai.activity.detail

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * How a stored sample stream is classified (Phase 6H-1C). The rule is deliberately one-directional:
 * FULL has to be earned by an internally consistent payload, everything else degrades to UNKNOWN rather
 * than to an optimistic guess. The numbers below are the ones Phase 6H-1B measured live.
 */
class SampleStreamFidelityTest {

    private fun fidelity(stored: Int, inPayload: Int, metrics: Int?, total: Int?, requested: Int? = 20000) =
        SampleStreamFidelity.of(stored, inPayload, metrics, total, requested)

    @Test
    fun `a consistent payload that reaches the native count is FULL`() {
        val result = fidelity(stored = 2784, inPayload = 2784, metrics = 2784, total = 2784)

        assertThat(result.completeness).isEqualTo(SampleCompleteness.FULL)
        assertThat(result.sourceMetricsCount).isEqualTo(2784)
        assertThat(result.sourceTotalMetricsCount).isEqualTo(2784)
        assertThat(result.requestedMaxChartSize).isEqualTo(20000)
    }

    @Test
    fun `fewer points than the activity has is DOWNSAMPLED`() {
        val result = fidelity(stored = 1399, inPayload = 1399, metrics = 1399, total = 2784)

        assertThat(result.completeness).isEqualTo(SampleCompleteness.DOWNSAMPLED)
        assertThat(result.sourceMetricsCount).isEqualTo(1399)
        assertThat(result.sourceTotalMetricsCount).isEqualTo(2784)
    }

    @Test
    fun `without a usable native count nothing is claimed`() {
        assertThat(fidelity(stored = 2784, inPayload = 2784, metrics = 2784, total = null).completeness)
            .isEqualTo(SampleCompleteness.UNKNOWN)
        // a negative count is not a count
        assertThat(fidelity(stored = 10, inPayload = 10, metrics = 10, total = -1).completeness)
            .isEqualTo(SampleCompleteness.UNKNOWN)
    }

    @Test
    fun `a payload that contradicts itself is never called FULL`() {
        // metricsCount claims more points than the payload actually carries
        val result = fidelity(stored = 2784, inPayload = 2784, metrics = 5000, total = 2784)

        assertThat(result.completeness).isEqualTo(SampleCompleteness.UNKNOWN)
        // the reported counts are still preserved exactly as the source gave them
        assertThat(result.sourceMetricsCount).isEqualTo(5000)
        assertThat(result.sourceTotalMetricsCount).isEqualTo(2784)
    }

    @Test
    fun `more stored rows than the source says exist is UNKNOWN, not FULL`() {
        assertThat(fidelity(stored = 3000, inPayload = 3000, metrics = 3000, total = 2784).completeness)
            .isEqualTo(SampleCompleteness.UNKNOWN)
    }

    @Test
    fun `a payload that reports no count of its own is judged on the native count alone`() {
        // metricsCount absent: nothing to contradict, so the native count still decides
        assertThat(fidelity(stored = 2784, inPayload = 2784, metrics = null, total = 2784).completeness)
            .isEqualTo(SampleCompleteness.FULL)
        assertThat(fidelity(stored = 1399, inPayload = 1399, metrics = null, total = 2784).completeness)
            .isEqualTo(SampleCompleteness.DOWNSAMPLED)
    }

    @Test
    fun `the requested size is recorded as given and never inferred from the counts`() {
        assertThat(fidelity(2784, 2784, 2784, 2784, requested = null).requestedMaxChartSize).isNull()
        assertThat(fidelity(2784, 2784, 2784, 2784, requested = 2000).requestedMaxChartSize).isEqualTo(2000)
    }
}
