package com.runningai.coach

import com.runningai.coach.CoachTestFixtures.DATE
import com.runningai.coach.CoachTestFixtures.draft
import com.runningai.coach.CoachTestFixtures.segment
import com.runningai.coach.CoachTestFixtures.thresholds
import com.runningai.training.IntensityClass
import com.runningai.training.PrimaryTargetType
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

    // ---- percent-LTHR / primaryTargetType / recovery shape rules (Phase 6H-7.1) -----------------
    // These apply via the plain (date, athlete) overload too - a legacy draft never populates the
    // new fields, so none of this can ever fire for one.

    @Test
    fun `a bpm target and a percent-LTHR target together are rejected`() {
        expectViolation(
            draft(segments = listOf(segment(durationMinutes = 30, hrMin = 140, hrMax = 150,
                hrPercentLthrMin = 65, hrPercentLthrMax = 75)), totalDurationMinutes = 30),
            "both an absolute heart-rate target and a %LTHR target",
        )
    }

    @Test
    fun `a half percent-LTHR range is rejected`() {
        expectViolation(
            draft(segments = listOf(segment(durationMinutes = 30, hrPercentLthrMin = 65)), totalDurationMinutes = 30),
            "only one bound of its %LTHR range",
        )
    }

    @Test
    fun `a non positive or inverted percent-LTHR range is rejected`() {
        expectViolation(
            draft(segments = listOf(segment(durationMinutes = 30, hrPercentLthrMin = -1, hrPercentLthrMax = 78)),
                totalDurationMinutes = 30),
            "%LTHR must be > 0",
        )
        expectViolation(
            draft(segments = listOf(segment(durationMinutes = 30, hrPercentLthrMin = 80, hrPercentLthrMax = 70)),
                totalDurationMinutes = 30),
            "%LTHR range is inverted",
        )
    }

    @Test
    fun `a well-formed percent-LTHR segment passes`() {
        assertThatCode {
            validator.validate(draft(segments = listOf(segment(durationMinutes = 30, hrPercentLthrMin = 65, hrPercentLthrMax = 78)),
                totalDurationMinutes = 30), DATE, thresholds())
        }.doesNotThrowAnyException()
    }

    @Test
    fun `primaryTargetType PACE requires a complete pace pair and no percent-LTHR`() {
        expectViolation(
            draft(segments = listOf(segment(durationMinutes = 30, primaryTargetType = PrimaryTargetType.PACE))),
            "declares primaryTargetType PACE but has no complete pace target",
        )
        expectViolation(
            draft(segments = listOf(segment(durationMinutes = 30, primaryTargetType = PrimaryTargetType.PACE,
                paceFast = 300, paceSlow = 320, hrPercentLthrMin = 65, hrPercentLthrMax = 78))),
            "declares primaryTargetType PACE but also carries a %LTHR target",
        )
    }

    @Test
    fun `primaryTargetType HEART_RATE requires a complete percent-LTHR pair and no pace`() {
        expectViolation(
            draft(segments = listOf(segment(durationMinutes = 30, primaryTargetType = PrimaryTargetType.HEART_RATE))),
            "declares primaryTargetType HEART_RATE but has no complete %LTHR target",
        )
        expectViolation(
            draft(segments = listOf(segment(durationMinutes = 30, primaryTargetType = PrimaryTargetType.HEART_RATE,
                hrPercentLthrMin = 65, hrPercentLthrMax = 78, paceFast = 300, paceSlow = 320))),
            "declares primaryTargetType HEART_RATE but also carries a pace target",
        )
    }

    @Test
    fun `primaryTargetType QUALITATIVE or NONE must carry no physiological numeric target`() {
        expectViolation(
            draft(segments = listOf(segment(durationMinutes = 30, primaryTargetType = PrimaryTargetType.QUALITATIVE,
                paceFast = 300, paceSlow = 320))),
            "declares primaryTargetType QUALITATIVE but also carries a pace target",
        )
        expectViolation(
            draft(segments = listOf(segment(durationMinutes = 30, primaryTargetType = PrimaryTargetType.NONE,
                hrPercentLthrMin = 65, hrPercentLthrMax = 78))),
            "declares primaryTargetType NONE but also carries a %LTHR target",
        )
    }

    @Test
    fun `primaryTargetType left null keeps the legacy inference - no new rule fires`() {
        assertThatCode {
            validator.validate(draft(segments = listOf(segment(durationMinutes = 30, paceFast = 300, paceSlow = 320))),
                DATE, thresholds())
        }.doesNotThrowAnyException()
    }

    @Test
    fun `a recovery object and legacy recoveryDurationMinutes together are rejected`() {
        val recovery = CoachTestFixtures.recovery()
        expectViolation(
            draft(segments = listOf(segment(durationMinutes = 5, repetitions = 3, recoveryMinutes = 2, recovery = recovery)),
                totalDurationMinutes = 21),
            "both a recovery object and the legacy recoveryDurationMinutes",
        )
    }

    @Test
    fun `a recovery object without repetitions is rejected`() {
        expectViolation(
            draft(segments = listOf(segment(durationMinutes = 5, recovery = CoachTestFixtures.recovery()))),
            "has a recovery object but no repetitions",
        )
    }

    @Test
    fun `a recovery object's own target follows the same shape rules`() {
        val malformed = CoachTestFixtures.recovery(primary = PrimaryTargetType.HEART_RATE, hrMin = 65, hrMax = null)
        expectViolation(
            draft(segments = listOf(segment(durationMinutes = 5, repetitions = 3, recovery = malformed))),
            "recovery",
            "only one bound of its %LTHR range",
        )
    }

    @Test
    fun `a recovery with a non positive duration is rejected`() {
        val zero = CoachTestFixtures.recovery().let {
            com.runningai.coach.WorkoutDraftRecovery(0, it.intensity, it.primaryTargetType, it.description,
                it.paceSecondsPerKmFast, it.paceSecondsPerKmSlow, it.heartRatePercentLthrMin, it.heartRatePercentLthrMax,
                it.treadmillSpeedKphMin, it.treadmillSpeedKphMax, it.inclinePercentMin, it.inclinePercentMax)
        }
        expectViolation(
            draft(segments = listOf(segment(durationMinutes = 5, repetitions = 3, recovery = zero))),
            "recovery duration must be > 0",
        )
    }

    @Test
    fun `duration arithmetic uses the recovery object's duration when present`() {
        val d = draft(
            segments = listOf(segment(SegmentType.MAIN, 5, IntensityClass.HARD, paceFast = 285, paceSlow = 300,
                repetitions = 3, recovery = CoachTestFixtures.recovery())),
            totalDurationMinutes = 21, // 3 * (5 + 2)
        )
        assertThatCode { validator.validate(d, DATE, thresholds()) }.doesNotThrowAnyException()
        expectViolation(d.copy(totalDurationMinutes = 20), "sum to 21")
    }

    // ---- V2 target completeness (Phase 6H-7.1) --------------------------------------------------

    private fun v2Context(athlete: AthleteThresholds) = TrainingContextV2(
        date = DATE,
        athlete = athlete,
        dataCoverage = DataCoverageV2(90, 0, 0, 0, 0, 90, 0, 28, 0, null, null),
        recovery = RecoveryContext(),
        trainingLoad = TrainingLoadContextV2(null, null, null, null, null, null, null, null, null, null),
        trainingRhythm = TrainingRhythmV2(0, 0, null, null, null, null, true, null, null),
        recentActivities = emptyList(),
        sourceFreshness = SourceFreshnessV2(null, null, null, null, null),
        constraints = SessionConstraints(),
    )

    @Test
    fun `V2 - a running segment with no target is rejected when the athlete has a threshold`() {
        val context = v2Context(thresholds())
        val d = draft(segments = listOf(segment(SegmentType.MAIN, 30, IntensityClass.EASY)))

        assertThatThrownBy { validator.validate(d, context) }
            .isInstanceOfSatisfying(WorkoutDraftValidationException::class.java) { e ->
                assertThat(e.violations.joinToString(" ")).contains("V2 target completeness")
            }
    }

    @Test
    fun `V2 - the same draft is accepted by the plain (date, athlete) overload - no retroactive V1 rule`() {
        assertThatCode {
            validator.validate(draft(segments = listOf(segment(SegmentType.MAIN, 30, IntensityClass.EASY))),
                DATE, thresholds())
        }.doesNotThrowAnyException()
    }

    @Test
    fun `V2 - pace-only athlete must use PACE, %LTHR is not required`() {
        val paceOnly = AthleteThresholds(null, 300)
        val context = v2Context(paceOnly)
        val withPace = draft(segments = listOf(segment(SegmentType.MAIN, 30, IntensityClass.EASY, paceFast = 345, paceSlow = 375)))
        assertThatCode { validator.validate(withPace, context) }.doesNotThrowAnyException()

        val withoutAny = draft(segments = listOf(segment(SegmentType.MAIN, 30, IntensityClass.EASY)))
        assertThatThrownBy { validator.validate(withoutAny, context) }.isInstanceOf(WorkoutDraftValidationException::class.java)
    }

    @Test
    fun `V2 - LTHR-only athlete must use HEART_RATE, pace is not required`() {
        val lthrOnly = AthleteThresholds(170, null)
        val context = v2Context(lthrOnly)
        val withHr = draft(segments = listOf(segment(SegmentType.MAIN, 30, IntensityClass.EASY,
            hrPercentLthrMin = 75, hrPercentLthrMax = 85)))
        assertThatCode { validator.validate(withHr, context) }.doesNotThrowAnyException()

        val withoutAny = draft(segments = listOf(segment(SegmentType.MAIN, 30, IntensityClass.EASY)))
        assertThatThrownBy { validator.validate(withoutAny, context) }.isInstanceOf(WorkoutDraftValidationException::class.java)
    }

    @Test
    fun `V2 - either pace or percent-LTHR satisfies completeness when both thresholds are known`() {
        val context = v2Context(thresholds())
        val withPace = draft(segments = listOf(segment(SegmentType.MAIN, 30, IntensityClass.EASY, paceFast = 345, paceSlow = 375)))
        val withHr = draft(segments = listOf(segment(SegmentType.MAIN, 30, IntensityClass.EASY,
            hrPercentLthrMin = 75, hrPercentLthrMax = 85)))
        assertThatCode { validator.validate(withPace, context) }.doesNotThrowAnyException()
        assertThatCode { validator.validate(withHr, context) }.doesNotThrowAnyException()
    }

    @Test
    fun `V2 - no threshold at all means QUALITATIVE is accepted, nothing is invented`() {
        val context = v2Context(AthleteThresholds(null, null))
        val qualitative = draft(segments = listOf(segment(SegmentType.MAIN, 30, IntensityClass.EASY)))

        assertThatCode { validator.validate(qualitative, context) }.doesNotThrowAnyException()
    }

    @Test
    fun `V2 - a REST-type segment is never subject to target completeness`() {
        val context = v2Context(thresholds())
        val d = draft(segments = listOf(
            segment(SegmentType.MAIN, 5, IntensityClass.HARD, paceFast = 285, paceSlow = 300),
            segment(SegmentType.REST, 2, IntensityClass.NONE),
        ), totalDurationMinutes = 7)

        assertThatCode { validator.validate(d, context) }.doesNotThrowAnyException()
    }
}
