package com.runningai.coach.eval

import com.runningai.coach.AthleteThresholds
import com.runningai.coach.CoachTestFixtures
import com.runningai.coach.DataCoverageV2
import com.runningai.coach.RecoveryContext
import com.runningai.coach.TrainingContextV2
import com.runningai.coach.TrainingLoadContextV2
import com.runningai.coach.TrainingRhythmV2
import com.runningai.coach.SessionConstraints
import com.runningai.coach.WorkoutDraft
import com.runningai.coach.WorkoutDraftRecovery
import com.runningai.coach.WorkoutDraftValidationException
import com.runningai.coach.WorkoutDraftValidator
import com.runningai.training.IntensityClass
import com.runningai.training.PrimaryTargetType
import com.runningai.training.SegmentType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Phase 6H-7.1 §60: synthetic [TrainingContextV2] scenarios exercising the V2-only target-
 * completeness rule ([WorkoutDraftValidator.validate] with a [com.runningai.coach.CoachTrainingContext]).
 *
 * Each scenario builds the kind of draft a coach *should* return for that athlete-threshold shape,
 * proves it is V2-valid, and - where the scenario exists specifically to test a rejection - proves
 * an incomplete answer is caught. Every value here is synthetic.
 */
class TrainingContextV2TargetCompletenessEvalTest {

    private val validator = WorkoutDraftValidator(CoachTestFixtures.properties())
    private val date: LocalDate = LocalDate.of(2026, 10, 2)

    private fun v2(athlete: AthleteThresholds) = TrainingContextV2(
        date = date,
        athlete = athlete,
        dataCoverage = DataCoverageV2(
            historyWindowDays = 90, supportedActivityCount = 10, analysedActivityCount = 10,
            fullSampleActivityCount = 10, intervalsMatchedActivityCount = 10,
            fitnessWindowDays = 90, fitnessDaysAvailable = 90,
            recoveryWindowDays = 28, recoveryDaysAvailable = 28,
            oldestActivityDate = date.minusDays(90), newestActivityDate = date.minusDays(1),
        ),
        recovery = RecoveryContext(),
        trainingLoad = TrainingLoadContextV2(
            sourceDate = date.minusDays(1), ageDays = 1, ctl = 45.0, atl = 40.0, derivedForm = 5.0,
            rampRate = 1.0, ctlLoad = 50.0, atlLoad = 45.0, sevenDaysAgo = null, twentyEightDaysAgo = null,
        ),
        trainingRhythm = TrainingRhythmV2(
            consecutiveActiveDays = 1, consecutiveRestDays = 0,
            lastRunDate = date.minusDays(1), daysSinceLastRun = 1,
            lastLongRunDate = date.minusDays(6), daysSinceLastLongRun = 6,
            structuredIntervalDetectionAvailable = true,
            lastStructuredIntervalDate = null, daysSinceLastStructuredInterval = null,
        ),
        recentActivities = emptyList(),
        constraints = SessionConstraints(),
    )

    private fun assertV2Valid(draft: WorkoutDraft, athlete: AthleteThresholds) {
        validator.validate(draft, v2(athlete))
    }

    @Test
    fun `LTHR and pace both available - the coach may use either target per segment`() {
        val athlete = AthleteThresholds(lactateThresholdHeartRateBpm = 170, lactateThresholdPaceSecondsPerKm = 300)
        val draft = CoachTestFixtures.draft(
            date = date,
            workoutType = "EASY",
            segments = listOf(
                CoachTestFixtures.segment(SegmentType.WARM_UP, 10, IntensityClass.VERY_EASY,
                    primaryTargetType = PrimaryTargetType.HEART_RATE, hrPercentLthrMin = 65, hrPercentLthrMax = 78),
                CoachTestFixtures.segment(SegmentType.MAIN, 25, IntensityClass.EASY,
                    primaryTargetType = PrimaryTargetType.PACE, paceFast = 335, paceSlow = 360),
                CoachTestFixtures.segment(SegmentType.COOL_DOWN, 5, IntensityClass.VERY_EASY,
                    primaryTargetType = PrimaryTargetType.HEART_RATE, hrPercentLthrMin = 65, hrPercentLthrMax = 78),
            ),
        )

        assertV2Valid(draft, athlete)
    }

    @Test
    fun `LTHR only - every segment must use percent-LTHR, not pace`() {
        val athlete = AthleteThresholds(lactateThresholdHeartRateBpm = 170, lactateThresholdPaceSecondsPerKm = null)
        val complete = CoachTestFixtures.draft(
            date = date,
            segments = listOf(
                CoachTestFixtures.segment(SegmentType.WARM_UP, 10, IntensityClass.VERY_EASY,
                    primaryTargetType = PrimaryTargetType.HEART_RATE, hrPercentLthrMin = 65, hrPercentLthrMax = 78),
                CoachTestFixtures.segment(SegmentType.MAIN, 25, IntensityClass.EASY,
                    primaryTargetType = PrimaryTargetType.HEART_RATE, hrPercentLthrMin = 75, hrPercentLthrMax = 85),
                CoachTestFixtures.segment(SegmentType.COOL_DOWN, 5, IntensityClass.VERY_EASY,
                    primaryTargetType = PrimaryTargetType.HEART_RATE, hrPercentLthrMin = 65, hrPercentLthrMax = 78),
            ),
        )
        assertV2Valid(complete, athlete)

        // A main segment left qualitative (no pace possible, no %LTHR given) must be rejected.
        val incomplete = complete.copy(segments = complete.segments.map {
            if (it.type == SegmentType.MAIN) {
                it.copy(primaryTargetType = PrimaryTargetType.QUALITATIVE,
                    heartRatePercentLthrMin = null, heartRatePercentLthrMax = null)
            } else it
        })
        assertThatThrownBy { validator.validate(incomplete, v2(athlete)) }
            .isInstanceOf(WorkoutDraftValidationException::class.java)
            .hasMessageContaining("V2 target completeness")
    }

    @Test
    fun `pace only - every segment must use pace, not percent-LTHR`() {
        val athlete = AthleteThresholds(lactateThresholdHeartRateBpm = null, lactateThresholdPaceSecondsPerKm = 300)
        val draft = CoachTestFixtures.draft(
            date = date,
            segments = listOf(
                CoachTestFixtures.segment(SegmentType.WARM_UP, 10, IntensityClass.VERY_EASY,
                    primaryTargetType = PrimaryTargetType.PACE, paceFast = 420, paceSlow = 450),
                CoachTestFixtures.segment(SegmentType.MAIN, 25, IntensityClass.EASY,
                    primaryTargetType = PrimaryTargetType.PACE, paceFast = 335, paceSlow = 360),
                CoachTestFixtures.segment(SegmentType.COOL_DOWN, 5, IntensityClass.VERY_EASY,
                    primaryTargetType = PrimaryTargetType.PACE, paceFast = 420, paceSlow = 450),
            ),
        )

        assertV2Valid(draft, athlete)
    }

    @Test
    fun `no thresholds at all - the completeness rule does not apply, qualitative is accepted`() {
        val athlete = AthleteThresholds(lactateThresholdHeartRateBpm = null, lactateThresholdPaceSecondsPerKm = null)
        val draft = CoachTestFixtures.draft(
            date = date,
            segments = listOf(
                CoachTestFixtures.segment(SegmentType.WARM_UP, 10, IntensityClass.VERY_EASY),
                CoachTestFixtures.segment(SegmentType.MAIN, 25, IntensityClass.EASY),
                CoachTestFixtures.segment(SegmentType.COOL_DOWN, 5, IntensityClass.VERY_EASY),
            ),
        )

        assertV2Valid(draft, athlete)
    }

    @Test
    fun `interval with a targeted easy-jog recovery between repetitions`() {
        val athlete = AthleteThresholds(lactateThresholdHeartRateBpm = 170, lactateThresholdPaceSecondsPerKm = 300)
        val draft = CoachTestFixtures.draft(
            date = date,
            workoutType = "THRESHOLD",
            segments = listOf(
                CoachTestFixtures.segment(SegmentType.WARM_UP, 12, IntensityClass.VERY_EASY,
                    primaryTargetType = PrimaryTargetType.HEART_RATE, hrPercentLthrMin = 65, hrPercentLthrMax = 78),
                CoachTestFixtures.segment(SegmentType.MAIN, 5, IntensityClass.HARD,
                    primaryTargetType = PrimaryTargetType.PACE, paceFast = 285, paceSlow = 300,
                    repetitions = 3,
                    recovery = CoachTestFixtures.recovery(durationMinutes = 2, primary = PrimaryTargetType.HEART_RATE,
                        hrMin = 65, hrMax = 75)),
                CoachTestFixtures.segment(SegmentType.COOL_DOWN, 10, IntensityClass.VERY_EASY,
                    primaryTargetType = PrimaryTargetType.HEART_RATE, hrPercentLthrMin = 65, hrPercentLthrMax = 78),
            ),
            totalDurationMinutes = 43,
        )

        assertV2Valid(draft, athlete)
    }

    @Test
    fun `passive recovery - NONE is a legitimate deliberate choice, never force-filled`() {
        val athlete = AthleteThresholds(lactateThresholdHeartRateBpm = 170, lactateThresholdPaceSecondsPerKm = 300)
        val draft = CoachTestFixtures.draft(
            date = date,
            workoutType = "INTERVAL",
            segments = listOf(
                CoachTestFixtures.segment(SegmentType.MAIN, 3, IntensityClass.HARD,
                    primaryTargetType = PrimaryTargetType.PACE, paceFast = 270, paceSlow = 285,
                    repetitions = 4,
                    recovery = WorkoutDraftRecovery(
                        durationMinutes = 2, intensity = IntensityClass.NONE,
                        primaryTargetType = PrimaryTargetType.NONE,
                        description = "Stand and walk",
                    )),
            ),
            totalDurationMinutes = 20,
        )

        assertV2Valid(draft, athlete)
    }

    @Test
    fun `treadmill - a device target is still required alongside the speed-incline cue`() {
        val athlete = AthleteThresholds(lactateThresholdHeartRateBpm = 170, lactateThresholdPaceSecondsPerKm = 300)
        val withTarget = CoachTestFixtures.draft(
            date = date,
            segments = listOf(
                CoachTestFixtures.segment(SegmentType.MAIN, 30, IntensityClass.EASY,
                    primaryTargetType = PrimaryTargetType.PACE, paceFast = 335, paceSlow = 360,
                    speedMin = 9.5, speedMax = 10.2, inclineMin = 1.0, inclineMax = 1.0),
            ),
            totalDurationMinutes = 30,
        )
        assertV2Valid(withTarget, athlete)

        // A treadmill speed cue alone, with no pace or %LTHR, is not completeness: the speed is an
        // operational cue, not the physiological target the athlete's known threshold supports.
        val speedOnly = withTarget.copy(segments = withTarget.segments.map {
            it.copy(primaryTargetType = PrimaryTargetType.QUALITATIVE,
                paceSecondsPerKmFast = null, paceSecondsPerKmSlow = null)
        })
        assertThatThrownBy { validator.validate(speedOnly, v2(athlete)) }
            .isInstanceOf(WorkoutDraftValidationException::class.java)
            .hasMessageContaining("V2 target completeness")
    }

    @Test
    fun `the same drafts remain valid under V1 (no completeness rule applied)`() {
        // Sanity: calling the plain 3-arg validate (V1 path, or no context at all) never applies the
        // V2-only rule, so a qualitative-only draft is fine even when the athlete has thresholds.
        val athlete = AthleteThresholds(lactateThresholdHeartRateBpm = 170, lactateThresholdPaceSecondsPerKm = 300)
        val qualitativeOnly = CoachTestFixtures.draft(
            date = date,
            segments = listOf(CoachTestFixtures.segment(SegmentType.MAIN, 30, IntensityClass.EASY)),
            totalDurationMinutes = 30,
        )

        validator.validate(qualitativeOnly, date, athlete)
    }
}
