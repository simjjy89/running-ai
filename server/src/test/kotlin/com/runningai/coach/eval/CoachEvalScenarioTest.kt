package com.runningai.coach.eval

import com.runningai.coach.AthleteThresholds
import com.runningai.coach.CoachAssessment
import com.runningai.coach.CoachTestFixtures
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
     * given, says recovery is unknown (which is always true in this build), and echoes any reported
     * fatigue. It stands in for "a coach that read the context".
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
                recoveryAssessment = "Recovery data is unavailable, so readiness is unknown.",
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
