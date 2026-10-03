package com.runningai.coach.claude

import com.runningai.coach.AiCoach
import com.runningai.coach.AiCoachException
import com.runningai.coach.CoachProperties
import com.runningai.coach.CoachTrainingContext
import com.runningai.coach.WorkoutDraft
import com.runningai.coach.WorkoutDraftValidationException
import com.runningai.coach.WorkoutDraftValidator
import org.slf4j.LoggerFactory

/**
 * [AiCoach] backed by the locally installed Claude Code CLI.
 *
 * It only orchestrates: prompt -> CLI -> parse -> validate. The coaching judgement is entirely the
 * model's, and this class never alters the workout it gets back — a draft that fails validation is
 * rejected, never repaired, because a repaired workout would be one nobody actually prescribed.
 *
 * This class has no dependency on any publishing, Intervals or Garmin type, by construction and by
 * architecture test: an AI coach can design a session and nothing else.
 */
class ClaudeAiCoach(
    private val promptBuilder: ClaudeCoachPromptBuilder,
    private val cliClient: ClaudeCliClient,
    private val parser: ClaudeCoachResponseParser,
    private val validator: WorkoutDraftValidator,
    private val properties: CoachProperties,
) : AiCoach {

    private val log = LoggerFactory.getLogger(ClaudeAiCoach::class.java)

    override fun createWorkout(context: CoachTrainingContext): WorkoutDraft =
        ask(context, promptBuilder.createPrompt(context), version = 1, what = "create")

    override fun reviseWorkout(
        context: CoachTrainingContext,
        currentDraft: WorkoutDraft,
        userRequest: String,
    ): WorkoutDraft {
        require(userRequest.isNotBlank()) { "userRequest must not be blank" }
        return ask(
            context,
            promptBuilder.revisePrompt(context, currentDraft, userRequest),
            version = currentDraft.version + 1,
            what = "revise",
        ).copy(draftGroupId = currentDraft.draftGroupId)
    }

    private fun ask(context: CoachTrainingContext, prompt: String, version: Int, what: String): WorkoutDraft {
        // Date and version only: the prompt, the context and the response body are never logged.
        log.info("AI coach {} requested: provider=CLAUDE date={} version={}", what, context.date, version)

        val result = cliClient.run(promptBuilder.systemPrompt(), prompt)
        val draft = parser.parse(result.stdout, context.date, properties.claude.model, version)

        try {
            validator.validate(draft, context)
        } catch (e: WorkoutDraftValidationException) {
            log.warn("AI coach {} rejected by validation: violations={}", what, e.violations)
            throw AiCoachException(
                AiCoachException.Reason.VALIDATION_FAILED,
                "The coach response failed safety validation: ${e.violations.joinToString("; ")}",
                e,
            )
        }

        log.info(
            "AI coach {} accepted: date={} version={} type={} durationMinutes={} segments={}",
            what, draft.date, draft.version, draft.workoutType, draft.totalDurationMinutes, draft.segments.size,
        )
        return draft
    }
}
