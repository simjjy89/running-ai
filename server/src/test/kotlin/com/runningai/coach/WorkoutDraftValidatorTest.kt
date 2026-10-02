package com.runningai.coach

import com.runningai.coach.CoachTestFixtures.DATE
import com.runningai.coach.CoachTestFixtures.draft
import com.runningai.coach.CoachTestFixtures.segment
import com.runningai.coach.CoachTestFixtures.thresholds
import com.runningai.training.IntensityClass
import com.runningai.training.SegmentType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Hard-safety validation only. No Spring context, no network. All values synthetic. */
class WorkoutDraftValidatorTest {

    private val validator = WorkoutDraftValidator(CoachTestFixtures.properties())

    private fun expectViolation(d: WorkoutDraft, vararg contains: String) {
        assertThatThrownBy { validator.validate(d, DATE, thresholds()) }
            .isInstanceOfSatisfying(WorkoutDraftValidationException::class.java) { e ->
                assertThat(e.violations).isNotEmpty
                contains.forEach { needle -> assertThat(e.violations.joinToString(" ")).contains(needle) }
            }
    }

    @Test
    fun `a well formed draft passes`() {
        assertThatCode { validator.validate(draft(), DATE, thresholds()) }.doesNotThrowAnyException()
    }

    @Test
    fun `a draft with no segments is rejected`() {
        expectViolation(draft(segments = emptyList(), totalDurationMinutes = 40), "no segments")
    }

    @Test
    fun `segment durations must sum to the declared total`() {
        expectViolation(draft(totalDurationMinutes = 99), "sum to")
    }

    @Test
    fun `a repeated block counts every repetition and its recovery towards the total`() {
        val intervals = listOf(
            segment(SegmentType.WARM_UP, 10, IntensityClass.VERY_EASY),
            segment(SegmentType.MAIN, 3, IntensityClass.HARD, repetitions = 5, recoveryMinutes = 2),
            segment(SegmentType.COOL_DOWN, 10, IntensityClass.VERY_EASY),
        )
        // 10 + 5*(3+2) + 10 = 45
        assertThatCode { validator.validate(draft(segments = intervals, totalDurationMinutes = 45), DATE, thresholds()) }
            .doesNotThrowAnyException()
        expectViolation(draft(segments = intervals, totalDurationMinutes = 23), "sum to")
    }

    @Test
    fun `a non positive segment duration is rejected`() {
        expectViolation(draft(segments = listOf(segment(durationMinutes = 0)), totalDurationMinutes = 0), "duration must be")
    }

    @Test
    fun `a non positive total duration is rejected`() {
        expectViolation(draft(segments = listOf(segment(durationMinutes = 30)), totalDurationMinutes = -5),
            "totalDurationMinutes must be")
    }

    @Test
    fun `an absurd total duration is rejected by the safety ceiling`() {
        val long = listOf(segment(durationMinutes = 600))
        expectViolation(draft(segments = long, totalDurationMinutes = 600), "safety ceiling")
    }

    @Test
    fun `a date that is not the requested date is rejected`() {
        expectViolation(draft(date = DATE.plusDays(1)), "does not match the requested date")
    }

    @Test
    fun `an empty rationale is rejected`() {
        expectViolation(draft(assessment = CoachTestFixtures.assessment(rationale = "  ")), "rationale is blank")
    }

    @Test
    fun `an inverted pace range is rejected`() {
        // pace is seconds/km, so "fast" must be the SMALLER number
        expectViolation(
            draft(segments = listOf(segment(durationMinutes = 30, paceFast = 330, paceSlow = 310)),
                totalDurationMinutes = 30),
            "pace range is inverted",
        )
    }

    @Test
    fun `an inverted heart rate range is rejected`() {
        expectViolation(
            draft(segments = listOf(segment(durationMinutes = 30, hrMin = 170, hrMax = 150)),
                totalDurationMinutes = 30),
            "range is inverted",
        )
    }

    @Test
    fun `a negative pace or heart rate is rejected`() {
        expectViolation(
            draft(segments = listOf(segment(durationMinutes = 30, paceFast = -10, paceSlow = 330)),
                totalDurationMinutes = 30),
            "must be > 0",
        )
        expectViolation(
            draft(segments = listOf(segment(durationMinutes = 30, hrMin = -1, hrMax = 150)),
                totalDurationMinutes = 30),
            "must be > 0",
        )
    }

    @Test
    fun `a non finite treadmill speed is rejected`() {
        expectViolation(
            draft(segments = listOf(segment(durationMinutes = 30, speedMin = Double.NaN, speedMax = 10.0)),
                totalDurationMinutes = 30),
            "not a finite number",
        )
        expectViolation(
            draft(segments = listOf(segment(durationMinutes = 30, speedMin = 8.0,
                speedMax = Double.POSITIVE_INFINITY)), totalDurationMinutes = 30),
            "not a finite number",
        )
    }

    @Test
    fun `a wildly implausible pace against the athlete threshold is rejected`() {
        // threshold pace 300 s/km; 60 s/km would be a world-record-beating pace -> unit error, not coaching
        expectViolation(
            draft(segments = listOf(segment(durationMinutes = 30, paceFast = 60, paceSlow = 70)),
                totalDurationMinutes = 30),
            "implausible",
        )
    }

    @Test
    fun `a wildly implausible heart rate against the athlete LTHR is rejected`() {
        expectViolation(
            draft(segments = listOf(segment(durationMinutes = 30, hrMin = 20, hrMax = 25)),
                totalDurationMinutes = 30),
            "implausible",
        )
    }

    @Test
    fun `an aggressive but realistic target is accepted - coaching policy is not second-guessed`() {
        // Faster than threshold pace and above LTHR: a real interval session, which the validator
        // must NOT reject. Only physically impossible values are rejected.
        val hard = listOf(
            segment(SegmentType.WARM_UP, 10, IntensityClass.VERY_EASY),
            segment(SegmentType.MAIN, 20, IntensityClass.HARD, paceFast = 270, paceSlow = 285,
                hrMin = 175, hrMax = 185),
            segment(SegmentType.COOL_DOWN, 10, IntensityClass.VERY_EASY),
        )
        assertThatCode { validator.validate(draft(segments = hard, totalDurationMinutes = 40), DATE, thresholds()) }
            .doesNotThrowAnyException()
    }

    @Test
    fun `targets are not plausibility-checked when the athlete has no threshold profile`() {
        val noProfile = AthleteThresholds(null, null)
        val d = draft(segments = listOf(segment(durationMinutes = 30, paceFast = 100, paceSlow = 120)),
            totalDurationMinutes = 30)

        assertThatCode { validator.validate(d, DATE, noProfile) }.doesNotThrowAnyException()
    }

    @Test
    fun `a downhill incline is allowed but an absurd one is rejected`() {
        val downhill = listOf(segment(durationMinutes = 30, inclineMin = -2.0, inclineMax = 0.0))
        assertThatCode { validator.validate(draft(segments = downhill, totalDurationMinutes = 30), DATE, thresholds()) }
            .doesNotThrowAnyException()

        expectViolation(
            draft(segments = listOf(segment(durationMinutes = 30, inclineMin = 0.0, inclineMax = 95.0)),
                totalDurationMinutes = 30),
            "out of range",
        )
    }

    @Test
    fun `non positive repetitions are rejected`() {
        expectViolation(
            draft(segments = listOf(segment(durationMinutes = 10, repetitions = 0)), totalDurationMinutes = 10),
            "repetitions must be",
        )
    }

    @Test
    fun `every violation is reported, not just the first`() {
        val bad = draft(
            date = DATE.plusDays(3),
            segments = listOf(segment(durationMinutes = -1)),
            totalDurationMinutes = -1,
            assessment = CoachTestFixtures.assessment(rationale = ""),
        )
        assertThatThrownBy { validator.validate(bad, DATE, thresholds()) }
            .isInstanceOfSatisfying(WorkoutDraftValidationException::class.java) { e ->
                assertThat(e.violations.size).isGreaterThanOrEqualTo(3)
            }
    }

    // ---- REST as a first-class draft (Phase 6F.1) -------------------------------------------

    @Test
    fun `a rest day with 0 minutes and no segments passes`() {
        val rest = CoachTestFixtures.restDraft()

        assertThat(rest.isRest).isTrue()
        assertThatCode { validator.validate(rest, DATE, thresholds()) }.doesNotThrowAnyException()
        assertThatCode { validator.validate(rest, DATE, AthleteThresholds(null, null)) }.doesNotThrowAnyException()
    }

    @Test
    fun `a rest day with a positive duration is rejected`() {
        expectViolation(CoachTestFixtures.restDraft().copy(totalDurationMinutes = 5),
            "REST workout must have totalDurationMinutes 0 but was 5")
    }

    @Test
    fun `a rest day padded with segments is rejected`() {
        val padded = CoachTestFixtures.restDraft().copy(
            segments = listOf(segment(SegmentType.MAIN, 10, IntensityClass.VERY_EASY)),
            totalDurationMinutes = 0,
        )
        expectViolation(padded, "REST workout must have no segments but has 1")
        // padding with a matching total is still a padded rest day
        expectViolation(padded.copy(totalDurationMinutes = 10), "must have no segments", "totalDurationMinutes 0")
    }

    @Test
    fun `a non rest workout with 0 minutes is rejected`() {
        expectViolation(draft(segments = listOf(segment(durationMinutes = 30)), totalDurationMinutes = 0),
            "totalDurationMinutes must be > 0 but was 0")
    }

    @Test
    fun `a non rest workout with no segments is rejected even with a total`() {
        expectViolation(draft(workoutType = "EASY", segments = emptyList(), totalDurationMinutes = 0),
            "workout has no segments", "totalDurationMinutes must be > 0")
        expectViolation(draft(workoutType = "RECOVERY", segments = emptyList(), totalDurationMinutes = 20),
            "workout has no segments")
    }

    @Test
    fun `a non rest workout below the minimum duration is still rejected`() {
        expectViolation(draft(segments = listOf(segment(durationMinutes = 3)), totalDurationMinutes = 3), "below the minimum")
    }

    @Test
    fun `only the exact REST type is a rest day - a lowercase variant gets no exemption`() {
        val lower = CoachTestFixtures.restDraft().copy(workoutType = "rest")

        assertThat(lower.isRest).isFalse()
        expectViolation(lower, "workout has no segments")
    }

    @Test
    fun `a rest day still needs the requested date, a title and a rationale`() {
        val rest = CoachTestFixtures.restDraft()
        expectViolation(rest.copy(date = DATE.plusDays(1)), "does not match the requested date")
        expectViolation(rest.copy(title = " "), "title is blank")
        expectViolation(rest.copy(assessment = rest.assessment.copy(rationale = "")), "rationale is blank")
    }
}
