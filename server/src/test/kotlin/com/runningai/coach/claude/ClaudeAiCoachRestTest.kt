package com.runningai.coach.claude

import com.fasterxml.jackson.databind.ObjectMapper
import com.runningai.coach.AiCoachException
import com.runningai.coach.CoachTestFixtures
import com.runningai.coach.WorkoutDraftValidator
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * The real [ClaudeAiCoach] (real prompt builder, parser and validator) handling a rest day, with only
 * the CLI process replaced by a canned envelope. No Claude call is made. Phase 6F.1.
 */
class ClaudeAiCoachRestTest {

    private val properties = CoachTestFixtures.properties()

    /** Returns a fixed CLI envelope and records the prompts it was given. */
    private class CannedCli(private val coachJson: String) : ClaudeCliClient(CoachTestFixtures.properties()) {
        var userPrompt: String? = null

        override fun run(systemPrompt: String, userPrompt: String): ClaudeCliResult {
            this.userPrompt = userPrompt
            val envelope = ObjectMapper().writeValueAsString(
                mapOf("is_error" to false, "subtype" to "success", "result" to coachJson),
            )
            return ClaudeCliResult(0, envelope, "")
        }
    }

    private fun coach(cli: ClaudeCliClient) = ClaudeAiCoach(
        ClaudeCoachPromptBuilder(), cli, ClaudeCoachResponseParser(ObjectMapper()), WorkoutDraftValidator(properties),
        properties,
    )

    private fun restJson(total: Int = 0, segments: String = "[]") = """
        {"assessment":{"recovery":"Athlete reports exhaustion; wearables normal","loadTrend":"Stable",
         "selectedWorkoutType":"REST","rationale":"You feel drained, so today is a full rest day.",
         "warnings":["Resume easy running once you feel recovered"]},
         "workout":{"title":"Rest Day","totalDurationMinutes":$total,"segments":$segments}}
    """.trimIndent()

    @Test
    fun `a rest day answer is accepted as a REST draft with no segments`() {
        val cli = CannedCli(restJson())

        val draft = coach(cli).createWorkout(CoachTestFixtures.context())

        assertThat(draft.isRest).isTrue()
        assertThat(draft.totalDurationMinutes).isZero()
        assertThat(draft.segments).isEmpty()
        // the coach was told how to express a rest day, but not when to choose one
        assertThat(cli.userPrompt).contains("REST is a valid choice", "must be an empty array []")
    }

    @Test
    fun `a rest day revision of an easy run is accepted`() {
        val draft = coach(CannedCli(restJson())).reviseWorkout(
            CoachTestFixtures.context(), CoachTestFixtures.draft(), "I'm exhausted today",
        )

        assertThat(draft.isRest).isTrue()
        assertThat(draft.version).isEqualTo(2)
    }

    @Test
    fun `a padded rest day answer is rejected, not repaired`() {
        val padded = restJson(
            total = 10,
            segments = """[{"type":"MAIN","durationMinutes":10,"intensity":"VERY_EASY"}]""",
        )

        assertThatThrownBy { coach(CannedCli(padded)).createWorkout(CoachTestFixtures.context()) }
            .isInstanceOfSatisfying(AiCoachException::class.java) {
                assertThat(it.reason).isEqualTo(AiCoachException.Reason.VALIDATION_FAILED)
                assertThat(it.message).contains("REST workout must have no segments")
            }
    }

    @Test
    fun `no Spring rule decides rest - the prompt contains no recovery threshold that forces it`() {
        val prompt = ClaudeCoachPromptBuilder().createPrompt(CoachTestFixtures.context())
        val system = ClaudeCoachPromptBuilder().systemPrompt()

        assertThat(prompt + system).doesNotContainIgnoringCase("must rest")
        assertThat(prompt + system).doesNotContainIgnoringCase("choose rest when")
        assertThat(system).doesNotContain("\"")
    }
}
