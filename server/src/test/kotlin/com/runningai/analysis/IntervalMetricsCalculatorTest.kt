package com.runningai.analysis

import com.runningai.activity.detail.LapData
import com.runningai.activity.detail.SampleData
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Repeatability and recovery heart rate (Phase 6H-4), with hand-checked numbers. `speedCvPercent` is a
 * POPULATION standard deviation over the repetitions present; the tests below pin that choice down, since
 * a switch to the sample formula would change every value without failing anything else.
 */
class IntervalMetricsCalculatorTest {

    private val extractor = IntervalStructureExtractor()
    private val calculator = IntervalMetricsCalculator(extractor)
    private val sessionMetrics = SessionMetricsCalculator()
    private val base: Instant = Instant.parse("2026-09-28T21:30:00Z")

    private fun lap(index: Int, intensity: String, step: Int, startOffset: Long, duration: Double, speed: Double?, hr: Double?) =
        LapData(
            lapIndex = index, startTime = base.plusSeconds(startOffset), durationSeconds = duration,
            averageSpeed = speed, averageHeartRateBpm = hr, intensityType = intensity, workoutStepIndex = step,
        )

    private fun sample(i: Int, elapsed: Double, hr: Double?) =
        SampleData(sampleIndex = i, sampleTime = base.plusSeconds(elapsed.toLong()), elapsedSeconds = elapsed, heartRate = hr)

    /** Work/recovery pairs; work speeds and heart rates come from the arguments. */
    private fun session(speeds: List<Double?>, hrs: List<Double?>): List<LapData> = buildList {
        var offset = 0L
        speeds.indices.forEach { i ->
            add(lap(size + 1, "ACTIVE", 1, offset, 60.0, speeds[i], hrs[i]))
            offset += 60
            add(lap(size + 1, "RECOVERY", 2, offset, 120.0, 2.0, 130.0))
            offset += 120
        }
    }

    private fun enrich(laps: List<LapData>, samples: List<SampleData> = emptyList()): List<IntervalGroup> {
        val blocks = extractor.blocks(laps)
        val timeline = sessionMetrics.timeline(samples)
        return calculator.enrich(extractor.groups(blocks), blocks, timeline, samples.firstOrNull()?.sampleTime)
    }

    @Test
    fun `identical repetitions have no spread at all`() {
        val group = enrich(session(speeds = listOf(5.0, 5.0, 5.0), hrs = listOf(170.0, 170.0, 170.0))).single()

        assertThat(group.workRepCount).isEqualTo(3)
        assertThat(group.meanSpeed).isEqualTo(5.0)
        assertThat(group.speedStdDev).isEqualTo(0.0)
        assertThat(group.speedCvPercent).isEqualTo(0.0)
        assertThat(group.lastVsFirstSpeedChangePercent).isEqualTo(0.0)
        assertThat(group.hrProgressionBpm).isEqualTo(0.0)
    }

    @Test
    fun `the spread is the population standard deviation over the repetitions present`() {
        // speeds 4, 5, 6 -> mean 5; population variance = ((1)+(0)+(1))/3 = 2/3; sd = 0.8164965809...
        // CV = 0.8164965809 / 5 * 100 = 16.32993162%
        // (the sample formula would give sd = 1.0 and CV = 20%, so this pins the choice down)
        val group = enrich(session(speeds = listOf(4.0, 5.0, 6.0), hrs = listOf(160.0, 165.0, 170.0))).single()

        assertThat(group.meanSpeed).isEqualTo(5.0)
        assertThat(group.speedStdDev).isCloseTo(0.816496580927726, within(1e-12))
        assertThat(group.speedCvPercent).isCloseTo(16.32993161855452, within(1e-9))
    }

    @Test
    fun `last against first is reported for speed and heart rate`() {
        // speed 5.0 -> 4.75 = -5% ; heart rate 168 -> 180 = +12 bpm
        val group = enrich(session(speeds = listOf(5.0, 4.9, 4.75), hrs = listOf(168.0, 174.0, 180.0))).single()

        assertThat(group.firstRepSpeed).isEqualTo(5.0)
        assertThat(group.lastRepSpeed).isEqualTo(4.75)
        assertThat(group.lastVsFirstSpeedChangePercent).isCloseTo(-5.0, within(1e-9))
        assertThat(group.firstRepHr).isEqualTo(168.0)
        assertThat(group.lastRepHr).isEqualTo(180.0)
        assertThat(group.hrProgressionBpm).isEqualTo(12.0)
    }

    @Test
    fun `repetitions without a reported speed leave the spread null, not zero`() {
        val group = enrich(session(speeds = listOf(null, null), hrs = listOf(170.0, 172.0))).single()

        assertThat(group.meanSpeed).isNull()
        assertThat(group.speedStdDev).isNull()
        assertThat(group.speedCvPercent).isNull()
        assertThat(group.lastVsFirstSpeedChangePercent).isNull()
        // heart rate is still reported
        assertThat(group.hrProgressionBpm).isEqualTo(2.0)
    }

    @Test
    fun `recovery heart rate is the fall across the recovery that follows the last repetition`() {
        // two work/recovery pairs of 60 s work + 120 s recovery; the last recovery runs 240..360 s.
        // Window is 10 s at each end: start = mean(hr at 240, 250) = 175, end = mean(hr at 350, 360) = 140
        val laps = session(speeds = listOf(5.0, 5.0), hrs = listOf(170.0, 174.0))
        val samples = buildList {
            var t = 0.0
            var i = 0
            while (t <= 360.0) {
                val hr = when {
                    t < 240.0 -> 170.0
                    t <= 250.0 -> 175.0
                    t >= 350.0 -> 140.0
                    else -> 155.0
                }
                add(sample(i++, t, hr))
                t += 10.0
            }
        }

        val group = enrich(laps, samples).single()

        assertThat(group.recovery.startHr).isEqualTo(175.0)
        assertThat(group.recovery.endHr).isEqualTo(140.0)
        assertThat(group.recovery.dropBpm).isEqualTo(35.0)
        assertThat(group.recovery.durationSeconds).isEqualTo(120.0)
    }

    @Test
    fun `a sparse recovery still reports what its samples support`() {
        val laps = session(speeds = listOf(5.0, 5.0), hrs = listOf(170.0, 174.0))
        // only two samples inside the final recovery (240..360): no sample lands in either 10 s window,
        // so the first and last usable readings are used instead of inventing a window average
        val samples = listOf(sample(0, 0.0, 150.0), sample(1, 300.0, 170.0), sample(2, 355.0, 138.0))

        val group = enrich(laps, samples).single()

        assertThat(group.recovery.startHr).isEqualTo(170.0)
        assertThat(group.recovery.endHr).isEqualTo(138.0)
        assertThat(group.recovery.dropBpm).isEqualTo(32.0)
    }

    @Test
    fun `without samples only the recovery duration the laps state is reported`() {
        val group = enrich(session(speeds = listOf(5.0, 5.0), hrs = listOf(170.0, 174.0))).single()

        assertThat(group.recovery.durationSeconds).isEqualTo(120.0)
        assertThat(group.recovery.startHr).isNull()
        assertThat(group.recovery.endHr).isNull()
        assertThat(group.recovery.dropBpm).isNull()
    }

    @Test
    fun `recovery samples without a heart rate yield no drop`() {
        val laps = session(speeds = listOf(5.0, 5.0), hrs = listOf(170.0, 174.0))
        val samples = (0..36).map { sample(it, it * 10.0, if (it * 10.0 >= 240.0) null else 160.0) }

        val group = enrich(laps, samples).single()

        assertThat(group.recovery.startHr).isNull()
        assertThat(group.recovery.dropBpm).isNull()
        assertThat(group.recovery.durationSeconds).isEqualTo(120.0)
    }

    @Test
    fun `a group whose last repetition has no recovery after it reports no recovery change`() {
        val laps = listOf(
            lap(1, "ACTIVE", 1, 0, 60.0, 5.0, 170.0),
            lap(2, "RECOVERY", 2, 60, 120.0, 2.0, 140.0),
            lap(3, "ACTIVE", 1, 180, 60.0, 5.0, 172.0),
            lap(4, "COOLDOWN", 3, 240, 120.0, 2.5, 130.0),
        )

        val group = enrich(laps, (0..36).map { sample(it, it * 10.0, 150.0) }).single()

        assertThat(group.workRepCount).isEqualTo(2)
        assertThat(group.recovery).isEqualTo(RecoveryHrChange())
    }
}
