package com.runningai.analysis

import com.runningai.activity.detail.SampleData
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Session metrics from a sample stream (Phase 6H-4). Pure arithmetic with hand-checked numbers: a formula
 * that silently drifts has to fail here, so every expectation below is computed by hand in the comment
 * rather than copied from a previous run.
 */
class SessionMetricsCalculatorTest {

    private val calculator = SessionMetricsCalculator()

    private fun sample(
        i: Int,
        elapsed: Double?,
        hr: Double? = null,
        speed: Double? = null,
        cadence: Double? = null,
        distance: Double? = null,
        time: Instant? = null,
    ) = SampleData(
        sampleIndex = i, sampleTime = time, elapsedSeconds = elapsed, distance = distance,
        speed = speed, heartRate = hr, cadence = cadence,
    )

    /** Four samples at 0/10/20/30 s: halves split at 15 s, so two samples each side. */
    private fun stream(hr: List<Double?>, speed: List<Double?>, cadence: List<Double?> = listOf(null, null, null, null)) =
        hr.indices.map { i -> sample(i, i * 10.0, hr[i], speed[i], cadence[i]) }

    @Test
    fun `an empty stream yields no metrics and no half split`() {
        val result = calculator.calculate(emptyList(), includeCadence = true)

        assertThat(result.validSampleCount).isZero()
        assertThat(result.analysisDurationSeconds).isNull()
        assertThat(result.halves).isEqualTo(HalfSplitMetrics())
    }

    @Test
    fun `a steady effort shows no change in either half`() {
        val result = calculator.calculate(
            stream(hr = listOf(150.0, 150.0, 150.0, 150.0), speed = listOf(3.0, 3.0, 3.0, 3.0)),
            includeCadence = false,
        )

        assertThat(result.halves.firstHalfAvgHr).isEqualTo(150.0)
        assertThat(result.halves.secondHalfAvgHr).isEqualTo(150.0)
        assertThat(result.halves.hrChangeBpm).isEqualTo(0.0)
        assertThat(result.halves.hrChangePercent).isEqualTo(0.0)
        assertThat(result.halves.speedChangePercent).isEqualTo(0.0)
        assertThat(result.halves.speedHrDecouplingPercent).isEqualTo(0.0)
        assertThat(result.analysisDurationSeconds).isEqualTo(30.0)
    }

    @Test
    fun `rising heart rate is reported as a rise, in bpm and percent`() {
        // halves: (150+150)/2 = 150 and (160+170)/2 = 165 -> +15 bpm, +10%
        val result = calculator.calculate(
            stream(hr = listOf(150.0, 150.0, 160.0, 170.0), speed = listOf(3.0, 3.0, 3.0, 3.0)),
            includeCadence = false,
        )

        assertThat(result.halves.firstHalfAvgHr).isEqualTo(150.0)
        assertThat(result.halves.secondHalfAvgHr).isEqualTo(165.0)
        assertThat(result.halves.hrChangeBpm).isEqualTo(15.0)
        assertThat(result.halves.hrChangePercent).isEqualTo(10.0)
    }

    @Test
    fun `falling speed is reported as a negative change`() {
        // halves: 4.0 and 3.0 -> (3-4)/4 = -25%
        val result = calculator.calculate(
            stream(hr = listOf(150.0, 150.0, 150.0, 150.0), speed = listOf(4.0, 4.0, 3.0, 3.0)),
            includeCadence = false,
        )

        assertThat(result.halves.firstHalfAvgSpeed).isEqualTo(4.0)
        assertThat(result.halves.secondHalfAvgSpeed).isEqualTo(3.0)
        assertThat(result.halves.speedChangePercent).isEqualTo(-25.0)
    }

    @Test
    fun `speed-HR decoupling follows (EF1 - EF2) over EF1`() {
        // EF1 = 4.0/160 = 0.025 ; EF2 = 3.0/200 = 0.015 ; (0.025-0.015)/0.025 = 40%
        val result = calculator.calculate(
            stream(hr = listOf(160.0, 160.0, 200.0, 200.0), speed = listOf(4.0, 4.0, 3.0, 3.0)),
            includeCadence = false,
        )

        assertThat(result.halves.firstHalfSpeedHrRatio).isCloseTo(0.025, within(1e-12))
        assertThat(result.halves.secondHalfSpeedHrRatio).isCloseTo(0.015, within(1e-12))
        assertThat(result.halves.speedHrDecouplingPercent).isCloseTo(40.0, within(1e-9))
    }

    @Test
    fun `decoupling is null when either half lacks a usable ratio`() {
        // no heart rate at all: the ratio cannot be formed, and nothing is substituted for it
        val noHr = calculator.calculate(
            stream(hr = listOf(null, null, null, null), speed = listOf(3.0, 3.0, 3.0, 3.0)),
            includeCadence = false,
        )
        assertThat(noHr.halves.firstHalfAvgHr).isNull()
        assertThat(noHr.halves.speedHrDecouplingPercent).isNull()
        assertThat(noHr.halves.hrChangePercent).isNull()
        // speed is still measured
        assertThat(noHr.halves.speedChangePercent).isEqualTo(0.0)

        // a heart rate of zero means "not measured", so it is not averaged in
        val zeroHr = calculator.calculate(
            stream(hr = listOf(0.0, 0.0, 0.0, 0.0), speed = listOf(3.0, 3.0, 3.0, 3.0)),
            includeCadence = false,
        )
        assertThat(zeroHr.halves.firstHalfAvgHr).isNull()
        assertThat(zeroHr.halves.speedHrDecouplingPercent).isNull()
    }

    @Test
    fun `a missing speed leaves speed metrics null without disturbing heart rate`() {
        val result = calculator.calculate(
            stream(hr = listOf(150.0, 150.0, 160.0, 160.0), speed = listOf(null, null, null, null)),
            includeCadence = false,
        )

        assertThat(result.halves.firstHalfAvgSpeed).isNull()
        assertThat(result.halves.speedChangePercent).isNull()
        assertThat(result.halves.hrChangeBpm).isEqualTo(10.0)
    }

    @Test
    fun `a zero speed is kept because standing still is part of the session`() {
        // halves: (0+2)/2 = 1.0 and (2+2)/2 = 2.0 -> +100%. No moving-time filter is applied.
        val result = calculator.calculate(
            stream(hr = listOf(150.0, 150.0, 150.0, 150.0), speed = listOf(0.0, 2.0, 2.0, 2.0)),
            includeCadence = false,
        )

        assertThat(result.halves.firstHalfAvgSpeed).isEqualTo(1.0)
        assertThat(result.halves.secondHalfAvgSpeed).isEqualTo(2.0)
        assertThat(result.halves.speedChangePercent).isEqualTo(100.0)
    }

    @Test
    fun `halves are split by elapsed time, not by the middle of the sample array`() {
        // 0, 1, 2, 90 s: the midpoint is 45 s, so three samples fall in the first half and one in the
        // second. An index split would have put two on each side and reported a different change.
        val samples = listOf(
            sample(0, 0.0, hr = 100.0), sample(1, 1.0, hr = 100.0),
            sample(2, 2.0, hr = 100.0), sample(3, 90.0, hr = 160.0),
        )

        val result = calculator.calculate(samples, includeCadence = false)

        assertThat(result.halves.firstHalfAvgHr).isEqualTo(100.0)
        assertThat(result.halves.secondHalfAvgHr).isEqualTo(160.0)
        assertThat(result.halves.hrChangeBpm).isEqualTo(60.0)
    }

    @Test
    fun `timestamps are the fallback when elapsed seconds are unusable`() {
        val base = Instant.parse("2026-09-28T21:30:00Z")
        val samples = listOf(
            sample(0, null, hr = 140.0, time = base),
            sample(1, null, hr = 140.0, time = base.plusSeconds(10)),
            sample(2, null, hr = 150.0, time = base.plusSeconds(20)),
            sample(3, null, hr = 150.0, time = base.plusSeconds(30)),
        )

        val result = calculator.calculate(samples, includeCadence = false)

        assertThat(result.analysisDurationSeconds).isEqualTo(30.0)
        assertThat(result.halves.hrChangeBpm).isEqualTo(10.0)
    }

    @Test
    fun `with no usable time base the halves stay null rather than falling back to an index split`() {
        val samples = listOf(
            sample(0, null, hr = 100.0), sample(1, null, hr = 100.0),
            sample(2, null, hr = 180.0), sample(3, null, hr = 180.0),
        )

        val result = calculator.calculate(samples, includeCadence = false)

        assertThat(result.validSampleCount).isEqualTo(4)
        assertThat(result.analysisDurationSeconds).isNull()
        assertThat(result.halves).isEqualTo(HalfSplitMetrics())
    }

    @Test
    fun `a time base that never advances is not a time base`() {
        val samples = (0..3).map { sample(it, 5.0, hr = 150.0) }

        assertThat(calculator.calculate(samples, includeCadence = false).halves).isEqualTo(HalfSplitMetrics())
    }

    @Test
    fun `cadence is measured for runs and left out otherwise`() {
        val samples = stream(
            hr = listOf(150.0, 150.0, 150.0, 150.0),
            speed = listOf(3.0, 3.0, 3.0, 3.0),
            cadence = listOf(180.0, 180.0, 171.0, 171.0),
        )

        val run = calculator.calculate(samples, includeCadence = true)
        // halves 180 and 171 -> -9 spm, -5%
        assertThat(run.halves.firstHalfAvgCadence).isEqualTo(180.0)
        assertThat(run.halves.cadenceChangeSpm).isEqualTo(-9.0)
        assertThat(run.halves.cadenceChangePercent).isEqualTo(-5.0)

        val notARun = calculator.calculate(samples, includeCadence = false)
        assertThat(notARun.halves.firstHalfAvgCadence).isNull()
        assertThat(notARun.halves.cadenceChangeSpm).isNull()
        assertThat(notARun.halves.cadenceChangePercent).isNull()
    }

    @Test
    fun `distance is what the stream covered, not what its last reading says`() {
        val samples = listOf(
            sample(0, 0.0, distance = 100.0), sample(1, 10.0, distance = 150.0),
            sample(2, 20.0, distance = 220.0), sample(3, 30.0, distance = 300.0),
        )

        assertThat(calculator.calculate(samples, includeCadence = false).analysisDistanceMeters).isEqualTo(200.0)
    }
}
