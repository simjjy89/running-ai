package com.runningai.analysis

import com.runningai.activity.detail.LapData
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Reading workout structure out of laps (Phase 6H-4). The shapes below are the ones Phase 6H-1B saw on a
 * real track session: a lap is not a step, steps repeat, and the final lap can carry no step at all.
 */
class IntervalStructureExtractorTest {

    private val extractor = IntervalStructureExtractor()
    private val base: Instant = Instant.parse("2026-09-28T21:30:00Z")

    private fun lap(
        index: Int,
        intensity: String?,
        step: Int?,
        duration: Double = 60.0,
        speed: Double? = 4.0,
        hr: Double? = 160.0,
        startOffset: Long = 0,
    ) = LapData(
        lapIndex = index, startTime = base.plusSeconds(startOffset), durationSeconds = duration,
        distanceMeters = speed?.let { it * duration }, averageSpeed = speed, averageHeartRateBpm = hr,
        maxHeartRateBpm = hr?.plus(5), intensityType = intensity, workoutIndex = 0, workoutStepIndex = step,
    )

    @Test
    fun `no laps means no blocks and no groups`() {
        assertThat(extractor.blocks(emptyList())).isEmpty()
        assertThat(extractor.groups(emptyList())).isEmpty()
    }

    @Test
    fun `consecutive laps on the same step collapse into one block`() {
        // live shape: one step spanning several laps
        val laps = listOf(
            lap(1, "WARMUP", 0, duration = 150.0),
            lap(2, "WARMUP", 0, duration = 150.0),
            lap(3, "ACTIVE", 1, duration = 100.0),
            lap(4, "ACTIVE", 1, duration = 100.0),
            lap(5, "ACTIVE", 1, duration = 100.0),
            lap(6, "COOLDOWN", 5, duration = 60.0),
        )

        val blocks = extractor.blocks(laps)

        assertThat(blocks).hasSize(3)
        assertThat(blocks.map { it.intensityType }).containsExactly("WARMUP", "ACTIVE", "COOLDOWN")
        assertThat(blocks[1].firstLapIndex).isEqualTo(3)
        assertThat(blocks[1].lastLapIndex).isEqualTo(5)
        assertThat(blocks[1].durationSeconds).isEqualTo(300.0)
    }

    @Test
    fun `the same step index coming back later is a separate block`() {
        val laps = listOf(
            lap(1, "ACTIVE", 2), lap(2, "RECOVERY", 3), lap(3, "ACTIVE", 2), lap(4, "RECOVERY", 3),
        )

        val blocks = extractor.blocks(laps)

        assertThat(blocks).hasSize(4)
        assertThat(blocks.map { it.workoutStepIndex }).containsExactly(2, 3, 2, 3)
    }

    @Test
    fun `a warmup-work-recovery-work-cooldown session yields one group of two repetitions`() {
        val laps = listOf(
            lap(1, "WARMUP", 0, duration = 300.0, speed = 3.0, startOffset = 0),
            lap(2, "ACTIVE", 2, duration = 20.0, speed = 5.0, hr = 170.0, startOffset = 300),
            lap(3, "RECOVERY", 3, duration = 100.0, speed = 2.0, hr = 140.0, startOffset = 320),
            lap(4, "ACTIVE", 2, duration = 20.0, speed = 4.8, hr = 178.0, startOffset = 420),
            lap(5, "RECOVERY", 3, duration = 100.0, speed = 2.0, hr = 142.0, startOffset = 440),
            lap(6, "COOLDOWN", 5, duration = 180.0, speed = 2.5, startOffset = 540),
        )

        val groups = extractor.groups(extractor.blocks(laps))

        assertThat(groups).hasSize(1)
        val group = groups.single()
        assertThat(group.workoutStepIndex).isEqualTo(2)
        assertThat(group.workRepCount).isEqualTo(2)
        assertThat(group.repetitions.map { it.repetitionIndex }).containsExactly(1, 2)
        assertThat(group.repetitions.map { it.averageSpeed }).containsExactly(5.0, 4.8)
        assertThat(group.repetitions.map { it.firstLapIndex }).containsExactly(2, 4)
    }

    @Test
    fun `a work block spanning several laps is one repetition, weighted by lap duration`() {
        val laps = listOf(
            lap(1, "ACTIVE", 1, duration = 100.0, speed = 5.0, hr = 170.0),
            lap(2, "ACTIVE", 1, duration = 300.0, speed = 4.0, hr = 180.0),
            lap(3, "RECOVERY", 2, duration = 60.0, speed = 2.0, hr = 140.0),
            lap(4, "ACTIVE", 1, duration = 100.0, speed = 4.5, hr = 175.0),
        )

        val group = extractor.groups(extractor.blocks(laps)).single()

        assertThat(group.workRepCount).isEqualTo(2)
        // (5.0*100 + 4.0*300) / 400 = 4.25, not the plain mean of 4.5
        assertThat(group.repetitions.first().averageSpeed).isEqualTo(4.25)
        assertThat(group.repetitions.first().durationSeconds).isEqualTo(400.0)
        assertThat(group.repetitions.first().maxHr).isEqualTo(185.0)
    }

    @Test
    fun `without a recovery between them, repeated work blocks are not called repetitions`() {
        val laps = listOf(
            lap(1, "ACTIVE", 1), lap(2, "COOLDOWN", 4), lap(3, "ACTIVE", 1),
        )

        assertThat(extractor.groups(extractor.blocks(laps))).isEmpty()
    }

    @Test
    fun `a single work occurrence is not a repetition group`() {
        val laps = listOf(lap(1, "WARMUP", 0), lap(2, "ACTIVE", 1), lap(3, "RECOVERY", 2))

        assertThat(extractor.groups(extractor.blocks(laps))).isEmpty()
    }

    @Test
    fun `laps without a step index are never grouped, however they look`() {
        // a plain run whose laps alternate fast and slow is still not an interval session
        val laps = listOf(
            lap(1, "ACTIVE", null, speed = 5.0), lap(2, "RECOVERY", null, speed = 2.0),
            lap(3, "ACTIVE", null, speed = 5.0), lap(4, "RECOVERY", null, speed = 2.0),
        )

        assertThat(extractor.groups(extractor.blocks(laps))).isEmpty()
    }

    @Test
    fun `an auto-lapped run with no structure at all yields one block and no groups`() {
        val laps = (1..10).map { lap(it, null, null, duration = 280.0, speed = 3.5) }

        val blocks = extractor.blocks(laps)

        assertThat(blocks).hasSize(1)
        assertThat(blocks.single().firstLapIndex).isEqualTo(1)
        assertThat(blocks.single().lastLapIndex).isEqualTo(10)
        assertThat(extractor.groups(blocks)).isEmpty()
    }

    @Test
    fun `the live track shape is read as four repetitions of one step`() {
        // WARMUP x4 -> ACTIVE x10 (step 1) -> ACTIVE(2)/RECOVERY(3) x4 -> COOLDOWN x3, last lap stepless
        val laps = buildList {
            repeat(4) { add(lap(size + 1, "WARMUP", 0, duration = 150.0, speed = 3.2)) }
            repeat(10) { add(lap(size + 1, "ACTIVE", 1, duration = 150.0, speed = 3.6)) }
            repeat(4) {
                add(lap(size + 1, "ACTIVE", 2, duration = 20.0, speed = 5.0 - it * 0.05, hr = 170.0 + it))
                add(lap(size + 1, "RECOVERY", 3, duration = 100.0, speed = 2.0, hr = 140.0))
            }
            repeat(2) { add(lap(size + 1, "COOLDOWN", 5, duration = 150.0, speed = 3.0)) }
            add(lap(size + 1, "COOLDOWN", null, duration = 10.0, speed = 2.0))
        }

        val blocks = extractor.blocks(laps)
        val groups = extractor.groups(blocks)

        // step 1 occurs once (no repetition), step 2 four times with recovery between: one group
        assertThat(groups).hasSize(1)
        assertThat(groups.single().workoutStepIndex).isEqualTo(2)
        assertThat(groups.single().workRepCount).isEqualTo(4)
        // the stepless final lap is its own block and belongs to no group
        assertThat(blocks.last().workoutStepIndex).isNull()
    }

    @Test
    fun `the recovery block after the last repetition is the one that follows it`() {
        val laps = listOf(
            lap(1, "ACTIVE", 1, startOffset = 0), lap(2, "RECOVERY", 2, startOffset = 60),
            lap(3, "ACTIVE", 1, startOffset = 160), lap(4, "RECOVERY", 2, startOffset = 220),
            lap(5, "COOLDOWN", 3, startOffset = 320),
        )
        val blocks = extractor.blocks(laps)
        val group = extractor.groups(blocks).single()

        val recovery = extractor.recoveryBlockAfter(blocks, group)

        assertThat(recovery).isNotNull
        assertThat(recovery!!.firstLapIndex).isEqualTo(4)
    }

    @Test
    fun `intensity names are matched without regard to case, and unknown names match nothing`() {
        val laps = listOf(
            lap(1, "active", 1), lap(2, "recovery", 2), lap(3, "Active", 1),
        )
        assertThat(extractor.groups(extractor.blocks(laps))).hasSize(1)

        val unknown = listOf(lap(1, "SPRINTING", 1), lap(2, "BREATHER", 2), lap(3, "SPRINTING", 1))
        assertThat(extractor.groups(extractor.blocks(unknown))).isEmpty()
    }
}
