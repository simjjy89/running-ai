package com.runningai.coach.eval

import com.fasterxml.jackson.databind.ObjectMapper
import com.runningai.coach.CoachTestFixtures
import com.runningai.coach.WorkoutDraftValidator
import com.runningai.coach.claude.ClaudeAiCoach
import com.runningai.coach.claude.ClaudeCliClient
import com.runningai.coach.claude.ClaudeCoachPromptBuilder
import com.runningai.coach.claude.ClaudeCoachResponseParser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestFactory

/**
 * Runs the evaluation scenarios against the **real** Claude CLI.
 *
 * Excluded from `gradlew test` by the `live-coach` tag (see `server/build.gradle`) because it makes
 * real, billable model calls and needs a logged-in CLI on the host. Run deliberately:
 *
 * ```
 * gradlew test -PliveCoachEval --tests "com.runningai.coach.eval.LiveClaudeCoachEvalTest"
 * ```
 *
 * It asserts only the scenario invariants — valid structure, hard constraints respected, context
 * not ignored — never one specific "correct" workout, so a competent coach that makes a different
 * reasonable choice still passes. It never publishes anything.
 */
@Tag("live-coach")
class LiveClaudeCoachEvalTest {

    private val properties = CoachTestFixtures.properties()
    private val coach = ClaudeAiCoach(
        ClaudeCoachPromptBuilder(),
        ClaudeCliClient(properties),
        ClaudeCoachResponseParser(ObjectMapper()),
        WorkoutDraftValidator(properties),
        properties,
    )

    @TestFactory
    fun `live Claude satisfies every scenario invariant`(): List<DynamicTest> =
        CoachEvalScenarios.ALL.map { scenario ->
            DynamicTest.dynamicTest("${scenario.id}: ${scenario.description}") {
                val draft = coach.createWorkout(scenario.context)

                val failures = scenario.invariants.mapNotNull { inv ->
                    inv.check(draft)?.let { "${inv.name}: $it" }
                }

                assertThat(failures)
                    .withFailMessage(
                        "Scenario %s produced '%s' (%d min, type %s) which violates: %s",
                        scenario.id, draft.title, draft.totalDurationMinutes,
                        draft.workoutType, failures,
                    )
                    .isEmpty()
            }
        }
}
