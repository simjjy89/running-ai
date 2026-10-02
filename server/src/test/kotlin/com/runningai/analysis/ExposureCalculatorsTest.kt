package com.runningai.analysis

import com.runningai.activity.detail.SampleData
import com.runningai.activity.detail.ZoneData
import com.runningai.activity.detail.ZoneType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

/** Zone and threshold exposure (Phase 6H-4). */
class ExposureCalculatorsTest {

    private val zones = ZoneExposureCalculator()
    private val threshold = ThresholdExposureCalculator(SessionMetricsCalculator())

    private fun zone(number: Int, seconds: Double?) =
        ZoneData(ZoneType.HEART_RATE, number, minValue = 100.0 + number * 10, durationSeconds = seconds)

    private fun sample(i: Int, elapsed: Double, hr: Double?) =
        SampleData(sampleIndex = i, elapsedSeconds = elapsed, heartRate = hr)

    @Test
    fun `five zones are kept as reported and shared out of their own total`() {
        val result = zones.calculate(
            listOf(zone(1, 100.0), zone(2, 200.0), zone(3, 300.0), zone(4, 200.0), zone(5, 200.0)),
        )

        assertThat(result.totalSeconds).isEqualTo(1000.0)
        assertThat(result.zoneSeconds).containsExactlyInAnyOrderEntriesOf(
            mapOf(1 to 100.0, 2 to 200.0, 3 to 300.0, 4 to 200.0, 5 to 200.0),
        )
        assertThat(result.zonePercent).containsExactlyInAnyOrderEntriesOf(
            mapOf(1 to 10.0, 2 to 20.0, 3 to 30.0, 4 to 20.0, 5 to 20.0),
        )
        assertThat(result.zonePercent.values.sum()).isCloseTo(100.0, within(1e-9))
    }

    @Test
    fun `a zone with no time is kept at zero, because the source did report it`() {
        val result = zones.calculate(listOf(zone(1, 90.0), zone(2, 10.0), zone(3, 0.0)))

        assertThat(result.zoneSeconds[3]).isEqualTo(0.0)
        assertThat(result.zonePercent[3]).isEqualTo(0.0)
        assertThat(result.totalSeconds).isEqualTo(100.0)
    }

    @Test
    fun `zones the source did not report are absent, never filled in with zero`() {
        val result = zones.calculate(listOf(zone(1, 60.0), zone(2, null), zone(5, 40.0)))

        assertThat(result.zoneSeconds.keys).containsExactlyInAnyOrder(1, 5)
        assertThat(result.zoneSeconds).doesNotContainKey(2)
        assertThat(result.totalSeconds).isEqualTo(100.0)
    }

    @Test
    fun `no zones at all is an empty exposure, not a zero one`() {
        val result = zones.calculate(emptyList())

        assertThat(result.zoneSeconds).isEmpty()
        assertThat(result.totalSeconds).isNull()
        assertThat(result.zonePercent).isEmpty()
    }

    @Test
    fun `threshold exposure integrates the gaps between samples instead of counting them`() {
        // 10 s gaps; HR >= 171 (95% of 180) from 20 s to 40 s inclusive, so the samples at 20 and 30
        // each contribute their 10 s gap. The final sample contributes nothing: nothing says how long
        // the last reading held.
        val samples = listOf(
            sample(0, 0.0, 150.0), sample(1, 10.0, 160.0), sample(2, 20.0, 175.0),
            sample(3, 30.0, 182.0), sample(4, 40.0, 150.0),
        )

        val result = threshold.calculate(samples, lactateThresholdHr = 180)

        assertThat(result.lthr95Seconds).isEqualTo(20.0)   // samples at 20 s and 30 s
        assertThat(result.lthr100Seconds).isEqualTo(10.0)  // only the sample at 30 s reaches 180
        assertThat(result.lthr90Seconds).isEqualTo(20.0)   // 162 bpm and up: only 20 s and 30 s (160 < 162)
    }

    @Test
    fun `uneven gaps are credited at their real length, not one second per sample`() {
        // a down-sampled stream: 0, 30, 90 s. The sample at 30 s is above threshold and owns 60 s.
        val samples = listOf(sample(0, 0.0, 120.0), sample(1, 30.0, 190.0), sample(2, 90.0, 120.0))

        val result = threshold.calculate(samples, lactateThresholdHr = 180)

        assertThat(result.lthr100Seconds).isEqualTo(60.0)
    }

    @Test
    fun `without an LTHR on file nothing is reported and nothing is substituted`() {
        val samples = listOf(sample(0, 0.0, 190.0), sample(1, 10.0, 190.0))

        listOf(null, 0, -5).forEach { lthr ->
            val result = threshold.calculate(samples, lactateThresholdHr = lthr)
            assertThat(result).isEqualTo(ThresholdExposure())
        }
    }

    @Test
    fun `without a usable time base no exposure can be integrated`() {
        val samples = listOf(
            SampleData(sampleIndex = 0, heartRate = 190.0),
            SampleData(sampleIndex = 1, heartRate = 190.0),
        )

        assertThat(threshold.calculate(samples, lactateThresholdHr = 180)).isEqualTo(ThresholdExposure())
    }

    @Test
    fun `samples without a heart rate contribute no time`() {
        val samples = listOf(
            sample(0, 0.0, null), sample(1, 10.0, 0.0), sample(2, 20.0, 190.0), sample(3, 30.0, 190.0),
        )

        val result = threshold.calculate(samples, lactateThresholdHr = 180)

        assertThat(result.lthr100Seconds).isEqualTo(10.0)  // the sample at 20 s only; 30 s is the last
    }
}
