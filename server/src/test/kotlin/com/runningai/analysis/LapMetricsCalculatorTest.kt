package com.runningai.analysis

import com.runningai.activity.detail.LapData
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

/** Lap-level metrics over every lap of the activity (Phase 6H-4). */
class LapMetricsCalculatorTest {

    private val calculator = LapMetricsCalculator()

    private fun lap(index: Int, speed: Double? = null, hr: Double? = null, cadence: Double? = null) =
        LapData(lapIndex = index, averageSpeed = speed, averageHeartRateBpm = hr, averageCadence = cadence)

    @Test
    fun `no laps is an empty result`() {
        assertThat(calculator.calculate(emptyList())).isEqualTo(LapMetrics())
    }

    @Test
    fun `even laps have no spread`() {
        val result = calculator.calculate((1..4).map { lap(it, speed = 3.5, hr = 160.0, cadence = 180.0) })

        assertThat(result.lapCount).isEqualTo(4)
        assertThat(result.speedMean).isEqualTo(3.5)
        assertThat(result.speedStdDev).isEqualTo(0.0)
        assertThat(result.speedCvPercent).isEqualTo(0.0)
        assertThat(result.hrMean).isEqualTo(160.0)
        assertThat(result.hrProgression).isEqualTo(0.0)
        assertThat(result.cadenceMean).isEqualTo(180.0)
    }

    @Test
    fun `the spread is the population standard deviation, like everywhere else in this package`() {
        // 4, 5, 6 -> mean 5, population sd 0.8164965809, CV 16.3299316%
        val result = calculator.calculate(listOf(lap(1, speed = 4.0), lap(2, speed = 5.0), lap(3, speed = 6.0)))

        assertThat(result.speedStdDev).isCloseTo(0.816496580927726, within(1e-12))
        assertThat(result.speedCvPercent).isCloseTo(16.32993161855452, within(1e-9))
    }

    @Test
    fun `heart-rate progression is the last reporting lap against the first, in source order`() {
        val result = calculator.calculate(
            // deliberately out of order: lap_index decides the order, not the list
            listOf(lap(3, hr = 180.0), lap(1, hr = 150.0), lap(2, hr = 165.0)),
        )

        assertThat(result.hrMean).isEqualTo(165.0)
        assertThat(result.hrProgression).isEqualTo(30.0)
    }

    @Test
    fun `laps that report nothing leave their metric null rather than zero`() {
        val result = calculator.calculate(listOf(lap(1), lap(2), lap(3)))

        assertThat(result.lapCount).isEqualTo(3)
        assertThat(result.speedMean).isNull()
        assertThat(result.speedStdDev).isNull()
        assertThat(result.speedCvPercent).isNull()
        assertThat(result.hrMean).isNull()
        assertThat(result.hrProgression).isNull()
        assertThat(result.cadenceMean).isNull()
    }

    @Test
    fun `a single lap has no progression to report`() {
        val result = calculator.calculate(listOf(lap(1, speed = 3.0, hr = 150.0)))

        assertThat(result.lapCount).isEqualTo(1)
        assertThat(result.hrMean).isEqualTo(150.0)
        assertThat(result.hrProgression).isNull()
        assertThat(result.speedStdDev).isEqualTo(0.0)
    }

    @Test
    fun `a warmup lap widens the lap spread - which is why interval consistency is computed separately`() {
        // 2.5 (warm-up) then four even 5.0 work laps: the lap-level CV is not an interval CV
        val result = calculator.calculate(
            listOf(lap(1, speed = 2.5)) + (2..5).map { lap(it, speed = 5.0) },
        )

        assertThat(result.speedCvPercent).isNotNull()
        assertThat(result.speedCvPercent!!).isGreaterThan(15.0)
    }
}
