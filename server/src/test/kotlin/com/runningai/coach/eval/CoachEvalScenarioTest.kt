package com.runningai.coach.eval

import com.runningai.coach.AthleteThresholds
import com.runningai.coach.CoachAssessment
import com.runningai.coach.CoachTestFixtures
import com.runningai.coach.RecoveryContext
import com.runningai.coach.RecoveryMeasurement
import com.runningai.coach.TrainingContext
import com.runningai.coach.WorkoutDraft
import com.runningai.coach.WorkoutDraftValidationException
import com.runningai.coach.WorkoutDraftValidator
import com.runningai.training.IntensityClass
import com.runningai.training.SegmentType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * CI run of the evaluation scenarios against a **deterministic fake coach**, not a live model.
 *
 * Two things are being verified here, and neither needs a paid API call:
 *  1. every scenario is well formed and its invariants actually run; and
 *  2. the invariants genuinely discriminate — a deliberately bad answer is caught.
 *
 * The same scenarios are run against the real Claude CLI by `LiveClaudeCoachEvalTest`, which is
 * tagged `live-coach` and excluded from `gradlew test`.
 */
class CoachEvalScenarioTest {

    private val validator = WorkoutDraftValidator(CoachTestFixtures.properties())

    /**
     * A reasonable deterministic answer for any scenario: it fits the available time when one is
     * given, describes exactly the recovery data it was given (and names what is missing or old),
     * and echoes any reported fatigue. It stands in for "a coach that read the context".
     */
    private fun goodAnswer(context: TrainingContext): WorkoutDraft {
        val budget = context.constraints.availableMinutes ?: 45
        val warmUp = (budget / 5).coerceAtLeast(5)
        val coolDown = (budget / 9).coerceAtLeast(5)
        val main = budget - warmUp - coolDown
        val fatigue = context.constraints.painOrFatigueFeedback
        val treadmill = context.constraints.environment == com.runningai.coach.TrainingEnvironment.TREADMILL

        return CoachTestFixtures.draft(
            date = context.date,
            segments = listOf(
                CoachTestFixtures.segment(SegmentType.WARM_UP, warmUp, IntensityClass.VERY_EASY),
                CoachTestFixtures.segment(
                    SegmentType.MAIN, main, IntensityClass.EASY,
                    paceFast = context.athlete.lactateThresholdPaceSecondsPerKm?.plus(35),
                    paceSlow = context.athlete.lactateThresholdPaceSecondsPerKm?.plus(70),
                    speedMin = if (treadmill) 9.0 else null,
                    speedMax = if (treadmill) 10.0 else null,
                ),
                CoachTestFixtures.segment(SegmentType.COOL_DOWN, coolDown, IntensityClass.VERY_EASY),
            ),
            totalDurationMinutes = budget,
            assessment = CoachAssessment(
                recoveryAssessment = recoveryAssessment(context.recovery),
                loadAssessment = "Recent load trend: ${context.weeklyContext.loadTrend}.",
                selectedWorkoutType = "EASY",
                rationale = if (fatigue != null) {
                    "You reported heavy, fatigued legs, so today stays easy and short."
                } else {
                    "A steady easy session keeps the week consistent without adding fatigue."
                },
                warnings = listOfNotNull(fatigue?.let { "Athlete reported: $it" }),
            ),
        )
    }

    private fun recoveryAssessment(r: RecoveryContext): String {
        if (!r.anyAvailable) return "Recovery data is unavailable, so readiness is unknown."
        val known = mutableListOf<String>()
        val missing = mutableListOf<String>()
        val ages = mutableListOf<Int>()
        fun add(name: String, m: RecoveryMeasurement?, unit: String) {
            if (m == null) {
                missing += name
            } else {
                known += "$name ${m.current}$unit against a baseline of ${m.baseline ?: "n/a"}"
                ages += m.ageDays
            }
        }
        add("HRV", r.hrv?.lastNightAvgMs, " ms")
        add("sleep", r.sleep?.durationHours, " h")
        add("resting HR", r.restingHeartRate?.bpm, " bpm")
        add("body battery", r.bodyBattery?.highest, "")
        add("stress", r.stress?.average, "")
        return buildString {
            append("Based on ").append(known.joinToString("; ")).append(".")
            if (missing.isNotEmpty()) append(" Unavailable: ").append(missing.joinToString(", ")).append(".")
            val oldest = ages.max()
            if (oldest >= 2) append(" These readings are $oldest days old and may not reflect today.")
        }
    }

    @TestFactory
    fun `every scenario is satisfied by a context-aware answer`(): List<DynamicTest> =
        CoachEvalScenarios.ALL.map { scenario ->
            DynamicTest.dynamicTest("${scenario.id}: ${scenario.description}") {
                val draft = goodAnswer(scenario.context)

                // a scenario answer must also survive real hard-safety validation
                validator.validate(draft, scenario.context.date, scenario.context.athlete)

                val failures = scenario.invariants.mapNotNull { inv ->
                    inv.check(draft)?.let { "${inv.name}: $it" }
                }
                assertThat(failures).isEmpty()
            }
        }

    @Test
    fun `there are at least ten scenarios and their ids are unique`() {
        assertThat(CoachEvalScenarios.ALL).hasSizeGreaterThanOrEqualTo(10)
        assertThat(CoachEvalScenarios.ALL.map { it.id }).doesNotHaveDuplicates()
        assertThat(CoachEvalScenarios.ALL).allSatisfy {
            assertThat(it.invariants).isNotEmpty
            assertThat(it.description).isNotBlank
        }
    }

    @Test
    fun `the time-limit invariant actually catches a session that overruns`() {
        val scenario = CoachEvalScenarios.ALL.single { it.id == "04-thirty-minutes-treadmill" }
        val tooLong = goodAnswer(scenario.context).let { d ->
            d.copy(
                totalDurationMinutes = 70,
                segments = listOf(CoachTestFixtures.segment(SegmentType.MAIN, 70, IntensityClass.EASY, speedMin = 9.0, speedMax = 10.0)),
            )
        }

        val failures = scenario.invariants.mapNotNull { it.check(tooLong) }

        assertThat(failures).anyMatch { it.contains("only 30 min were available") }
    }

    @Test
    fun `the missing-recovery invariant catches a coach that invents readiness`() {
        val scenario = CoachEvalScenarios.ALL.single { it.id == "10-no-recovery-data-and-no-thresholds" }
        val overconfident = goodAnswer(scenario.context).let {
            it.copy(
                assessment = it.assessment.copy(
                    recoveryAssessment = "Recovery is excellent and HRV is strong.",
                    rationale = "You are well recovered, so we can push today.",
                ),
            )
        }

        val failures = scenario.invariants.mapNotNull { it.check(overconfident) }

        assertThat(failures).anyMatch { it.contains("claims knowledge it does not have") }
    }

    @Test
    fun `the pain invariant catches a coach that ignores reported fatigue`() {
        val scenario = CoachEvalScenarios.ALL.single { it.id == "08-asks-for-hard-despite-poor-recovery" }
        val dismissive = goodAnswer(scenario.context).let {
            it.copy(
                assessment = it.assessment.copy(
                    recoveryAssessment = "Nothing of note.",
                    rationale = "Big interval set as requested.",
                    warnings = emptyList(),
                ),
            )
        }

        val failures = scenario.invariants.mapNotNull { it.check(dismissive) }

        assertThat(failures).anyMatch { it.contains("not reflected") }
    }

    @Test
    fun `there are ten recovery-aware scenarios covering the Phase 6F situations`() {
        val ids = CoachEvalScenarios.ALL.map { it.id }
        assertThat(ids).contains(
            "11-hrv-drop", "12-resting-hr-up", "13-short-sleep", "14-low-body-battery", "15-stress-up",
            "16-recovery-normal", "17-mixed-signals", "18-no-recovery-data", "19-stale-recovery",
            "20-fatigue-reported-wearable-normal",
        )
        assertThat(CoachEvalScenarios.ALL.single { it.id == "18-no-recovery-data" }.context.recovery.anyAvailable).isFalse()
        assertThat(CoachEvalScenarios.ALL.single { it.id == "19-stale-recovery" }.context.recovery.hrv!!.lastNightAvgMs.ageDays)
            .isEqualTo(6)
    }

    @Test
    fun `the missing-metric invariant catches a coach that invents an unrecorded HRV`() {
        val scenario = CoachEvalScenarios.ALL.single { it.id == "12-resting-hr-up" }
        val inventing = goodAnswer(scenario.context).let {
            it.copy(assessment = it.assessment.copy(
                recoveryAssessment = "Resting HR is up 8 bpm. HRV is solid at 55 ms, so you are fine.",
            ))
        }

        val failures = scenario.invariants.mapNotNull { it.check(inventing) }

        assertThat(failures).anyMatch { it.contains("cites missing metric(s) [hrv]") }
    }

    @Test
    fun `the missing-metric invariant accepts a coach that names the gap honestly`() {
        val scenario = CoachEvalScenarios.ALL.single { it.id == "12-resting-hr-up" }
        val honest = goodAnswer(scenario.context).let {
            it.copy(assessment = it.assessment.copy(
                recoveryAssessment = "Resting HR is 8 bpm above baseline. HRV and body battery were not recorded.",
            ))
        }

        assertThat(scenario.invariants.mapNotNull { it.check(honest) }).isEmpty()
    }

    @Test
    fun `the recovery-reflected invariant catches a coach that ignores the recovery data`() {
        val scenario = CoachEvalScenarios.ALL.single { it.id == "11-hrv-drop" }
        val ignoring = goodAnswer(scenario.context).let {
            it.copy(assessment = it.assessment.copy(recoveryAssessment = "Nothing of note.", warnings = emptyList()))
        }

        val failures = scenario.invariants.mapNotNull { it.check(ignoring) }

        assertThat(failures).anyMatch { it.contains("does not mention any recovery metric") }
    }

    @Test
    fun `the recovery-reflected invariant accepts a summary of all wearable readings`() {
        // Verbatim recovery assessments from the Phase 6F.1 live run (scenarios 20 and 19). Both
        // engage with the recovery data without naming a single metric; 6F's keyword list missed them.
        val base = goodAnswer(CoachEvalScenarios.ALL.single { it.id == "20-fatigue-reported-wearable-normal" }.context)
        listOf(
            "Wearable metrics all at baseline, but athlete reports exhaustion and dead legs, so readiness is poor",
            "Unknown for today: the latest recovery readings are 6 days old (all at baseline then), so they may not describe how you feel now",
        ).forEach { text ->
            val d = base.copy(assessment = base.assessment.copy(recoveryAssessment = text, warnings = emptyList()))
            assertThat(CoachEvalScenarios.reflectsRecovery.check(d)).isNull()
        }
    }

    @Test
    fun `the stale invariant catches a coach that treats six-day-old readings as current`() {
        val scenario = CoachEvalScenarios.ALL.single { it.id == "19-stale-recovery" }
        val naive = goodAnswer(scenario.context).let {
            it.copy(assessment = it.assessment.copy(
                recoveryAssessment = "HRV, sleep and resting HR are all on baseline today.",
                rationale = "Everything looks normal, so a steady run fits.",
            ))
        }

        val failures = scenario.invariants.mapNotNull { it.check(naive) }

        assertThat(failures).anyMatch { it.contains("treats days-old readings as current") }
    }

    @Test
    fun `the no-recovery scenario catches a coach that invents sleep data`() {
        val scenario = CoachEvalScenarios.ALL.single { it.id == "18-no-recovery-data" }
        val inventing = goodAnswer(scenario.context).let {
            it.copy(assessment = it.assessment.copy(recoveryAssessment = "You slept well and sleep score is high."))
        }

        val failures = scenario.invariants.mapNotNull { it.check(inventing) }

        assertThat(failures).isNotEmpty
    }

    @Test
    fun `the arithmetic invariant catches durations that do not add up`() {
        val scenario = CoachEvalScenarios.ALL.first()
        val inconsistent = goodAnswer(scenario.context).copy(totalDurationMinutes = 999)

        val failures = scenario.invariants.mapNotNull { it.check(inconsistent) }

        assertThat(failures).anyMatch { it.contains("segments sum to") }
    }

    @Test
    fun `a structurally broken answer fails both the invariants and hard validation`() {
        val scenario = CoachEvalScenarios.ALL.first()
        val broken = goodAnswer(scenario.context).copy(segments = emptyList())

        assertThat(scenario.invariants.mapNotNull { it.check(broken) }).isNotEmpty
        assertThatThrownBy { validator.validate(broken, scenario.context.date, AthleteThresholds(null, null)) }
            .isInstanceOf(WorkoutDraftValidationException::class.java)
    }

    @Test
    fun `scenarios carry no real athlete identifier`() {
        // Guards against a future contributor pasting a real export into the fixtures.
        val serialized = CoachEvalScenarios.ALL.joinToString(" ") { it.context.toString() }

        assertThat(serialized).doesNotContainIgnoringCase("garmin.com")
        assertThat(serialized).doesNotContain("@")
        assertThat(serialized).doesNotContainIgnoringCase("latitude")
        assertThat(serialized).doesNotContainIgnoringCase("longitude")
    }
}
