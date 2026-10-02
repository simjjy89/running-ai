package com.runningai.draftpublish

import com.runningai.coach.CoachTestFixtures.draft
import com.runningai.coach.CoachTestFixtures.restDraft
import com.runningai.coach.CoachTestFixtures.segment
import com.runningai.integration.intervals.IntervalsWorkoutRenderer
import com.runningai.training.CandidateTrainingType
import com.runningai.training.IntensityClass
import com.runningai.training.PaceTarget
import com.runningai.training.PrimaryTargetType
import com.runningai.training.SegmentType
import com.runningai.training.TreadmillTarget
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * The approved-draft bridge: WorkoutDraft -> StructuredWorkout -> the real, verified Intervals
 * renderer. Pure unit tests (no Spring, no network). The point throughout is that nothing the
 * athlete approved is changed, reordered, dropped or invented on the way to the rendered text.
 */
class WorkoutDraftStructuredWorkoutMapperTest {

    private val mapper = WorkoutDraftStructuredWorkoutMapper()
    private val validator = ApprovedWorkoutDraftPublishabilityValidator()
    private val renderer = IntervalsWorkoutRenderer()

    private fun render(d: com.runningai.coach.WorkoutDraft): String {
        assertThat(validator.problems(d)).isEmpty()
        val workout = mapper.map(d)
        assertThat(validator.mappedProblems(d, workout)).isEmpty()
        return renderer.render(workout).workoutText()
    }

    // ---- order, durations, intent ---------------------------------------------------------------

    @Test
    fun `segment order, types and durations are carried over exactly`() {
        val d = draft()

        val workout = mapper.map(d)

        assertThat(workout.steps().map { it.type() })
            .containsExactly(SegmentType.WARM_UP, SegmentType.MAIN, SegmentType.COOL_DOWN)
        assertThat(workout.steps().map { it.durationMinutes() }).containsExactly(10, 25, 5)
        assertThat(workout.steps().map { it.intensityClass() })
            .containsExactly(IntensityClass.VERY_EASY, IntensityClass.EASY, IntensityClass.VERY_EASY)
        assertThat(workout.asOfDate()).isEqualTo(d.date)
        assertThat(workout.totalDurationMinutes()).isEqualTo(40)
        assertThat(render(d)).isEqualTo("- Warm Up 10m\n- Main 25m\n- Cool Down 5m")
    }

    @Test
    fun `a qualitative easy segment has no target and none is invented`() {
        val step = mapper.map(draft()).steps()[1]

        assertThat(step.primaryTargetType()).isEqualTo(PrimaryTargetType.QUALITATIVE)
        assertThat(step.paceTarget()).isNull()
        assertThat(step.heartRateTarget()).isNull()
        assertThat(step.treadmillTarget()).isNull()
    }

    @Test
    fun `workout types map to an intent label only, never to a different workout`() {
        mapOf(
            "EASY" to CandidateTrainingType.EASY,
            "RECOVERY" to CandidateTrainingType.RECOVERY,
            "LONG" to CandidateTrainingType.LONG,
            "THRESHOLD" to CandidateTrainingType.QUALITY,
            "INTERVAL" to CandidateTrainingType.QUALITY,
            "TEMPO" to CandidateTrainingType.QUALITY,
        ).forEach { (type, intent) ->
            assertThat(WorkoutDraftStructuredWorkoutMapper.intentOf(type)).isEqualTo(intent)
        }
        assertThat(WorkoutDraftStructuredWorkoutMapper.intentOf("REST")).isNull()
        assertThat(WorkoutDraftStructuredWorkoutMapper.intentOf("CROSS_TRAINING")).isNull()
        assertThat(WorkoutDraftStructuredWorkoutMapper.intentOf("FARTLEK")).isNull()
    }

    @Test
    fun `a rest draft is never mapped`() {
        assertThatThrownBy { mapper.map(restDraft()) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { validator.problems(restDraft()) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    // ---- repetitions -----------------------------------------------------------------------------

    private val intervals = draft(
        workoutType = "INTERVAL",
        segments = listOf(
            segment(SegmentType.WARM_UP, 10, IntensityClass.VERY_EASY),
            segment(SegmentType.MAIN, 3, IntensityClass.HARD, paceFast = 270, paceSlow = 285,
                repetitions = 5, recoveryMinutes = 2),
            segment(SegmentType.COOL_DOWN, 10, IntensityClass.VERY_EASY),
        ),
    )

    @Test
    fun `a repeat block is expanded into work and recovery steps preserving the exact total`() {
        assertThat(intervals.totalDurationMinutes).isEqualTo(45)    // 10 + 5 x (3 + 2) + 10

        val workout = mapper.map(intervals)

        assertThat(workout.steps()).hasSize(1 + 5 * 2 + 1)
        assertThat(workout.steps().sumOf { it.durationMinutes() }).isEqualTo(45)
        assertThat(validator.mappedProblems(intervals, workout)).isEmpty()
        val block = workout.steps().subList(1, 11)
        block.forEachIndexed { i, step ->
            if (i % 2 == 0) {
                assertThat(step.type()).isEqualTo(SegmentType.MAIN)
                assertThat(step.durationMinutes()).isEqualTo(3)
                assertThat(step.intensityClass()).isEqualTo(IntensityClass.HARD)
                assertThat(step.paceTarget()).isEqualTo(PaceTarget(270, 285))
            } else {
                assertThat(step.type()).isEqualTo(SegmentType.REST)
                assertThat(step.durationMinutes()).isEqualTo(2)
                assertThat(step.primaryTargetType()).isEqualTo(PrimaryTargetType.NONE)
                assertThat(step.paceTarget()).isNull()
            }
        }
    }

    @Test
    fun `the rendered intervals keep order, pace and the recovery after the last repetition`() {
        val expectedBlock = (1..5).joinToString("\n") { "- Main 3m 4:30-4:45/km Pace\n- Rest 2m" }

        assertThat(render(intervals)).isEqualTo("- Warm Up 10m\n$expectedBlock\n- Cool Down 10m")
    }

    @Test
    fun `a repeat block without recovery expands to work steps only`() {
        val d = draft(
            workoutType = "TEMPO",
            segments = listOf(segment(SegmentType.MAIN, 8, IntensityClass.MODERATE, paceFast = 300, paceSlow = 310,
                repetitions = 3, recoveryMinutes = 0)),
        )

        val workout = mapper.map(d)

        assertThat(workout.steps().map { it.durationMinutes() }).containsExactly(8, 8, 8)
        assertThat(validator.mappedProblems(d, workout)).isEmpty()
    }

    @Test
    fun `a total that does not equal the mapped steps fails closed`() {
        val d = draft(totalDurationMinutes = 41)     // segments sum to 40

        assertThat(validator.mappedProblems(d, mapper.map(d)))
            .singleElement().asString().contains("sum to 40", "totals 41")
    }

    // ---- pace -----------------------------------------------------------------------------------

    @Test
    fun `a pace range is preserved exactly and becomes the primary target`() {
        val d = draft(segments = listOf(segment(SegmentType.MAIN, 40, IntensityClass.EASY, paceFast = 335, paceSlow = 355)))

        val step = mapper.map(d).steps().single()

        assertThat(step.primaryTargetType()).isEqualTo(PrimaryTargetType.PACE)
        assertThat(step.paceTarget()).isEqualTo(PaceTarget(335, 355))
        assertThat(render(d)).isEqualTo("- Main 40m 5:35-5:55/km Pace")
    }

    @Test
    fun `a single pace renders as one value`() {
        val d = draft(segments = listOf(segment(SegmentType.MAIN, 40, IntensityClass.EASY, paceFast = 340, paceSlow = 340)))

        assertThat(render(d)).isEqualTo("- Main 40m 5:40/km Pace")
    }

    @Test
    fun `a half pace range fails closed instead of inventing the other bound`() {
        val d = draft(segments = listOf(segment(SegmentType.MAIN, 40, IntensityClass.EASY, paceFast = 340)))

        assertThat(validator.problems(d)).anyMatch { it.contains("only one bound of its pace range") }
    }

    // ---- treadmill ------------------------------------------------------------------------------

    @Test
    fun `treadmill speed and incline are preserved and rendered as the Garmin-safe cue`() {
        val d = draft(segments = listOf(segment(SegmentType.MAIN, 30, IntensityClass.EASY,
            speedMin = 10.0, speedMax = 10.5, inclineMin = 1.0, inclineMax = 1.0)))

        val step = mapper.map(d).steps().single()

        assertThat(step.treadmillTarget()).isEqualTo(TreadmillTarget(10.0, 10.5, 1.0, 1.0))
        assertThat(render(d)).isEqualTo("- Main 10.0-10.5kph Incline1pct 30m")
    }

    @Test
    fun `treadmill cue and pace target are both rendered, cue before duration`() {
        val d = draft(segments = listOf(segment(SegmentType.MAIN, 30, IntensityClass.HARD, paceFast = 340, paceSlow = 340,
            speedMin = 10.6, speedMax = 10.6, inclineMin = 0.5, inclineMax = 1.5)))

        assertThat(render(d)).isEqualTo("- Main 10.6kph Incline0.5-1.5pct 30m 5:40/km Pace")
    }

    @Test
    fun `incline without speed is carried as an incline-only cue`() {
        val d = draft(segments = listOf(segment(SegmentType.MAIN, 30, IntensityClass.EASY, inclineMin = 2.0, inclineMax = 2.0)))

        assertThat(mapper.map(d).steps().single().treadmillTarget()).isEqualTo(TreadmillTarget(null, null, 2.0, 2.0))
        assertThat(render(d)).isEqualTo("- Main Incline2pct 30m")
    }

    @Test
    fun `a treadmill speed without an incline fails closed instead of inventing an incline`() {
        val d = draft(segments = listOf(segment(SegmentType.MAIN, 30, IntensityClass.EASY, speedMin = 10.0, speedMax = 10.0)))

        assertThat(validator.problems(d)).anyMatch { it.contains("treadmill speed but no incline") }
    }

    @Test
    fun `a negative incline fails closed because the cue cannot express it`() {
        val d = draft(segments = listOf(segment(SegmentType.MAIN, 30, IntensityClass.EASY, inclineMin = -1.0, inclineMax = 0.0)))

        assertThat(validator.problems(d)).anyMatch { it.contains("negative incline") }
    }

    @Test
    fun `half speed and half incline ranges fail closed`() {
        val d = draft(segments = listOf(segment(SegmentType.MAIN, 30, IntensityClass.EASY, speedMin = 10.0, inclineMax = 1.0)))

        assertThat(validator.problems(d))
            .anyMatch { it.contains("treadmill speed range") }
            .anyMatch { it.contains("incline range") }
    }

    // ---- heart rate (policy B: fail closed) -----------------------------------------------------

    @Test
    fun `a bpm heart-rate target fails closed rather than being converted or dropped`() {
        val d = draft(segments = listOf(segment(SegmentType.MAIN, 40, IntensityClass.EASY, hrMin = 140, hrMax = 150)))

        assertThat(validator.problems(d)).singleElement().asString().contains("heart-rate target in bpm")
    }

    @Test
    fun `heart rate next to a pace target also fails closed, because only the pace would be rendered`() {
        val d = draft(segments = listOf(segment(SegmentType.MAIN, 40, IntensityClass.EASY,
            paceFast = 335, paceSlow = 355, hrMin = 140, hrMax = 150)))

        assertThat(validator.problems(d)).anyMatch { it.contains("heart-rate target in bpm") }
    }

    @Test
    fun `the mapper never emits a heart-rate target`() {
        val workout = mapper.map(intervals)

        assertThat(workout.steps()).allMatch { it.heartRateTarget() == null }
    }

    // ---- representability -----------------------------------------------------------------------

    @Test
    fun `a hard segment without any numeric target fails closed`() {
        val d = draft(workoutType = "INTERVAL",
            segments = listOf(segment(SegmentType.MAIN, 20, IntensityClass.HARD)))

        assertThat(validator.problems(d)).anyMatch { it.contains("HARD but has no pace or treadmill-speed target") }
    }

    @Test
    fun `a moderate segment with a treadmill speed is representable`() {
        val d = draft(workoutType = "TEMPO", segments = listOf(segment(SegmentType.MAIN, 20, IntensityClass.MODERATE,
            speedMin = 12.0, speedMax = 12.0, inclineMin = 1.0, inclineMax = 1.0)))

        assertThat(validator.problems(d)).isEmpty()
    }

    @Test
    fun `cross training and unknown workout types fail closed`() {
        assertThat(validator.problems(draft(workoutType = "CROSS_TRAINING")))
            .anyMatch { it.contains("CROSS_TRAINING cannot be published") }
        assertThat(validator.problems(draft(workoutType = "FARTLEK")))
            .anyMatch { it.contains("FARTLEK cannot be published") }
    }
}
