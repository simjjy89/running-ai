package com.runningai.analysis

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.runningai.activity.detail.LapData
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

/** Garmin running-dynamics evidence, per lap, from each lap's own `extra_metrics` (Phase 7B-3). */
class RunningDynamicsCalculatorTest {

    private val calculator = RunningDynamicsCalculator()
    private val mapper = ObjectMapper()

    private fun extra(vararg pairs: Pair<String, Double?>): ObjectNode {
        val node = mapper.createObjectNode()
        pairs.forEach { (k, v) -> if (v != null) node.put(k, v) }
        return node
    }

    private fun lap(
        index: Int,
        speed: Double? = null,
        power: Double? = null,
        maxPower: Double? = null,
        stride: Double? = null,
        gct: Double? = null,
        vo: Double? = null,
        vr: Double? = null,
    ) = LapData(
        lapIndex = index,
        averageSpeed = speed,
        averagePower = power,
        maxPower = maxPower,
        extraMetrics = extra(
            "strideLength" to stride, "groundContactTime" to gct,
            "verticalOscillation" to vo, "verticalRatio" to vr,
        ),
    )

    @Test
    fun `a non-running activity gets every field null, even with laps present`() {
        val laps = listOf(lap(1, stride = 110.0), lap(2, stride = 108.0))

        val result = calculator.calculate(laps, isRun = false)

        assertThat(result).isEqualTo(RunningDynamicsMetrics())
    }

    @Test
    fun `no laps is an empty result`() {
        assertThat(calculator.calculate(emptyList(), isRun = true)).isEqualTo(RunningDynamicsMetrics())
    }

    @Test
    fun `a run with no dynamics in any lap reports every dynamics field null`() {
        val laps = listOf(lap(1, speed = 3.0), lap(2, speed = 3.0))

        val result = calculator.calculate(laps, isRun = true)

        assertThat(result.lapsWithDynamicsCount).isEqualTo(0)
        assertThat(result.avgStrideLengthCm).isNull()
        assertThat(result.avgRunningPowerWatts).isNull()
    }

    @Test
    fun `averages and lap-order halves are computed from only the laps that report a metric`() {
        val laps = listOf(
            lap(1, speed = 3.2, power = 340.0, stride = 112.0, gct = 235.0, vo = 8.7, vr = 7.6),
            lap(2, speed = 3.1, power = 330.0, stride = 108.0, gct = 240.0, vo = 8.6, vr = 7.9),
            lap(3, speed = 3.0, power = 320.0, stride = 104.0, gct = 248.0, vo = 8.6, vr = 8.2),
            lap(4, speed = 2.9, power = 300.0, stride = 101.0, gct = 255.0, vo = 8.65, vr = 8.5),
        )

        val result = calculator.calculate(laps, isRun = true)

        assertThat(result.lapsWithDynamicsCount).isEqualTo(4)
        assertThat(result.avgStrideLengthCm).isCloseTo(106.25, within(1e-9))
        assertThat(result.firstHalfAvgStrideLengthCm).isEqualTo(110.0) // laps 1-2
        assertThat(result.secondHalfAvgStrideLengthCm).isEqualTo(102.5) // laps 3-4
        assertThat(result.strideLengthChangePercent).isCloseTo(-6.818181818, within(1e-6))
        assertThat(result.avgGroundContactTimeMs).isCloseTo(244.5, within(1e-9))
        assertThat(result.groundContactTimeChangePercent).isGreaterThan(0.0) // GCT rose
        assertThat(result.avgRunningPowerWatts).isCloseTo(322.5, within(1e-9))
        assertThat(result.runningPowerChangePercent).isLessThan(0.0) // power fell
    }

    @Test
    fun `max running power is the maximum maxPower across laps, never the average`() {
        val laps = listOf(lap(1, maxPower = 400.0), lap(2, maxPower = 650.0), lap(3, maxPower = 420.0))

        assertThat(calculator.calculate(laps, isRun = true).maxRunningPowerWatts).isEqualTo(650.0)
    }

    @Test
    fun `fewer than two laps reporting a metric leaves its halves null, not a one-sided split`() {
        val laps = listOf(lap(1, stride = 110.0), lap(2 /* no stride */), lap(3, speed = 3.0))

        val result = calculator.calculate(laps, isRun = true)

        assertThat(result.lapsWithDynamicsCount).isEqualTo(1)
        assertThat(result.avgStrideLengthCm).isEqualTo(110.0)
        assertThat(result.firstHalfAvgStrideLengthCm).isNull()
        assertThat(result.secondHalfAvgStrideLengthCm).isNull()
        assertThat(result.strideLengthChangePercent).isNull()
    }

    @Test
    fun `same-pace comparison needs two laps at a similar pace that are also far enough apart`() {
        // Laps 1 and 2 share a near-identical pace but are adjacent (span 1) - too close to count.
        val tooClose = listOf(
            lap(1, speed = 3.2639999389648438, stride = 110.95, power = 333.0),
            lap(2, speed = 3.2639999389648438, stride = 108.0, power = 300.0),
        )
        assertThat(calculator.calculate(tooClose, isRun = true).samePaceStrideLengthChangeCm).isNull()

        // Laps 1 and 5 share a near-identical pace and are 4 laps apart - counts, and compares the
        // later lap minus the earlier one.
        val farEnough = listOf(
            lap(1, speed = 3.2639999389648438, stride = 110.95, power = 333.0),
            lap(2, speed = 2.9, stride = 100.0, power = 300.0),
            lap(3, speed = 2.9, stride = 100.0, power = 300.0),
            lap(4, speed = 2.9, stride = 100.0, power = 300.0),
            lap(5, speed = 3.263000011444092, stride = 109.88, power = 329.0),
        )
        val result = calculator.calculate(farEnough, isRun = true)
        assertThat(result.samePaceStrideLengthChangeCm).isCloseTo(109.88 - 110.95, within(1e-9))
        assertThat(result.samePaceRunningPowerChangeWatts).isCloseTo(329.0 - 333.0, within(1e-9))
    }

    /**
     * Real data: 2026-10-11 Seoul Race half marathon (activity_id=7), the same race analysed in
     * Phase 7A-1/7B-1, now re-evaluated with running dynamics (Phase 7B-3). Lap-level
     * strideLength/groundContactTime/verticalOscillation/verticalRatio and average_power/max_power
     * extracted directly from the stored `activity_lap` rows (confirmed via the live FR265 account,
     * cross-checked against the independently descriptor-confirmed sample-stream units for the same
     * activity). Values below are rounded to the precision actually stored.
     */
    @Test
    fun `2026-10-11 Seoul Race half marathon - stride length and ground contact time both move with pace`() {
        val laps = listOf(
            lap(1, 3.427000045776367, 340.0, 420.0, 114.2800048828125, 235.1999969482422, 8.719999694824219, 7.639999866485596),
            lap(2, 3.3550000190734863, 338.0, 353.0, 112.10999755859376, 237.3000030517578, 8.65999984741211, 7.71999979019165),
            lap(3, 3.299999952316284, 334.0, 348.0, 110.10000000000001, 238.5, 8.580000305175782, 7.789999961853027),
            lap(4, 3.2829999923706055, 326.0, 357.0, 109.3800048828125, 240.8000030517578, 8.61999969482422, 7.889999866485596),
            lap(5, 3.249000072479248, 330.0, 362.0, 110.1199951171875, 241.89999389648438, 8.719999694824219, 7.920000076293945),
            lap(6, 3.2119998931884766, 325.0, 387.0, 107.5, 243.6999969482422, 8.61999969482422, 8.010000228881836),
            lap(7, 3.2639999389648438, 333.0, 379.0, 110.95, 242.1999969482422, 8.790000152587892, 7.920000076293945),
            lap(8, 3.0889999866485596, 319.0, 358.0, 104.85000000000001, 248.0, 8.6, 8.239999771118164),
            lap(9, 3.302999973297119, 330.0, 653.0, 111.24000244140626, 244.60000610351562, 8.85, 8.029999732971191),
            lap(10, 3.127000093460083, 324.0, 565.0, 108.8800048828125, 248.1999969482422, 8.86999969482422, 8.210000038146973),
            lap(11, 3.253999948501587, 329.0, 430.0, 110.59000244140626, 244.89999389648438, 8.86999969482422, 8.050000190734863),
            lap(12, 3.253000020980835, 330.0, 357.0, 110.0800048828125, 243.6999969482422, 8.8, 7.989999771118164),
            lap(13, 3.2119998931884766, 328.0, 356.0, 109.46999511718751, 245.89999389648438, 8.85, 8.079999923706055),
            lap(14, 3.0980000495910645, 316.0, 331.0, 105.99000244140626, 249.1999969482422, 8.740000152587891, 8.239999771118164),
            lap(15, 3.0190000534057617, 309.0, 324.0, 104.39000244140625, 252.10000610351562, 8.75, 8.369999885559082),
            lap(16, 2.8989999294281, 298.0, 358.0, 100.52999877929688, 255.10000610351562, 8.59000015258789, 8.550000190734863),
            lap(17, 3.0840001106262207, 308.0, 332.0, 105.8199951171875, 248.6999969482422, 8.70999984741211, 8.229999542236328),
            lap(18, 2.9609999656677246, 297.0, 325.0, 101.85000000000001, 254.0, 8.640000152587891, 8.479999542236328),
            lap(19, 2.979000091552734, 300.0, 331.0, 102.94000244140625, 252.89999389648438, 8.680000305175781, 8.430000305175781),
            lap(20, 2.9519999027252197, 297.0, 317.0, 101.35000000000001, 255.10000610351562, 8.65, 8.529999732971191),
            lap(21, 3.263000011444092, 329.0, 419.0, 109.8800048828125, 247.3000030517578, 8.919999694824218, 8.140000343322754),
            lap(22, 2.809999942779541, 321.0, 422.0, 105.2800048828125, 248.5, 8.6, 8.380000114440918),
        )

        val result = calculator.calculate(laps, isRun = true)

        assertThat(result.lapsWithDynamicsCount).isEqualTo(22)
        // Second half (laps 12-22) ran noticeably shorter strides than the first half (laps 1-11).
        assertThat(result.strideLengthChangePercent!!).isCloseTo(-4.3, within(0.2))
        // ... with proportionally longer ground contact ...
        assertThat(result.groundContactTimeChangePercent!!).isCloseTo(3.3, within(0.2))
        // ... and essentially flat vertical oscillation (the "bounce" itself did not change) ...
        assertThat(result.verticalOscillationChangePercent!!).isCloseTo(0.0, within(0.2))
        // ... so vertical RATIO rose by about the same magnitude as stride length fell (VR = VO/stride).
        assertThat(result.verticalRatioChangePercent!!).isCloseTo(4.6, within(0.3))
        // Running power fell roughly in step with speed, not disproportionately.
        assertThat(result.runningPowerChangePercent!!).isCloseTo(-5.4, within(0.3))
        // Lap 9's one-off 653W max is a spike, not a sustained output - captured, not smoothed away.
        assertThat(result.maxRunningPowerWatts).isEqualTo(653.0)
        // Several early-race laps (around 5:03-5:07/km) recur at a near-identical pace late in the
        // race (lap 21's spurt); the widest-separated such pair drives this comparison. The resulting
        // stride-length and power differences are far smaller in magnitude than the -4.3%/-5.4%
        // seen across the whole first-half-vs-second-half split, showing the athlete could still
        // reproduce similar mechanics and output at a matched pace, late in the race.
        assertThat(result.samePaceStrideLengthChangeCm!!).isNotNull()
        assertThat(kotlin.math.abs(result.samePaceStrideLengthChangeCm!!)).isLessThan(3.0)
        assertThat(result.samePaceRunningPowerChangeWatts!!).isNotNull()
        assertThat(kotlin.math.abs(result.samePaceRunningPowerChangeWatts!!)).isLessThan(15.0)
    }
}
