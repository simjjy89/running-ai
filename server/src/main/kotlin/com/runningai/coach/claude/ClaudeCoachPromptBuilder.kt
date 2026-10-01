package com.runningai.coach.claude

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.runningai.coach.TrainingContext
import com.runningai.coach.WorkoutDraft
import org.springframework.stereotype.Component

/**
 * Builds the system and user prompts for a coach call.
 *
 * Two deliberate properties:
 *  - **No athlete value is hard-coded.** The system prompt describes the coaching role and the
 *    response contract and nothing else; every threshold, load number and constraint arrives in the
 *    user message as the serialized [TrainingContext]. Changing athlete simply changes that JSON.
 *  - **The context snapshot is deterministic**: the same [TrainingContext] serializes to the same
 *    bytes (map ordering is fixed, dates are ISO-8601), so a prompt can be reproduced and diffed.
 */
@Component
class ClaudeCoachPromptBuilder {

    private val mapper: ObjectMapper = ObjectMapper()
        .registerKotlinModule()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .enable(SerializationFeature.INDENT_OUTPUT)

    fun systemPrompt(): String = SYSTEM_PROMPT

    /** The initial "design today's session" message. */
    fun createPrompt(context: TrainingContext): String = buildString {
        appendLine("Design this athlete's training session for ${context.date}.")
        appendLine()
        appendLine("TRAINING CONTEXT (authoritative; a null value means the data is genuinely unavailable):")
        appendLine(snapshot(context))
        appendLine()
        appendLine(RESPONSE_CONTRACT)
    }

    /** The "the athlete asked for a change, design it again" message. */
    fun revisePrompt(context: TrainingContext, currentDraft: WorkoutDraft, userRequest: String): String =
        buildString {
            appendLine("You previously designed the session below for this athlete on ${context.date}.")
            appendLine("The athlete has now asked for a change. Design the session again, taking their")
            appendLine("request seriously while still applying your own coaching judgement: keep the")
            appendLine("training purpose intact where you can, and say so in the rationale if the request")
            appendLine("forces a real trade-off. Do not simply trim numbers from the previous workout.")
            appendLine()
            appendLine("ATHLETE'S REQUEST (verbatim):")
            appendLine(userRequest.trim())
            appendLine()
            appendLine("YOUR PREVIOUS WORKOUT (version ${currentDraft.version}):")
            appendLine(mapper.writeValueAsString(PreviousWorkout.of(currentDraft)))
            appendLine()
            appendLine("TRAINING CONTEXT (unchanged; a null value means the data is genuinely unavailable):")
            appendLine(snapshot(context))
            appendLine()
            appendLine(RESPONSE_CONTRACT)
        }

    /** The deterministic JSON snapshot handed to the model. */
    fun snapshot(context: TrainingContext): String = mapper.writeValueAsString(context)

    /** Trimmed view of a previous draft: the workout and its reasoning, no storage/identity fields. */
    private data class PreviousWorkout(
        val title: String,
        val workoutType: String,
        val totalDurationMinutes: Int,
        val rationale: String,
        val segments: List<com.runningai.coach.WorkoutDraftSegment>,
    ) {
        companion object {
            fun of(d: WorkoutDraft) = PreviousWorkout(
                d.title, d.workoutType, d.totalDurationMinutes, d.assessment.rationale, d.segments,
            )
        }
    }

    private companion object {

        val SYSTEM_PROMPT = """
            You are an experienced endurance running coach designing one training session at a time
            for a single athlete, inside an automated system. You are the one making the training
            decisions: the software around you collects data, checks your answer for safety and
            stores it, but it does not choose the session type for you.

            How to think about the session:
            - Apply endurance training principles: progressive overload, adequate recovery, an
              easy/hard distribution that keeps most volume genuinely easy, and specificity to the
              athlete's goal when one is stated.
            - Read the recent load honestly. Consider how much the athlete has been training, how
              that compares to the previous week, how many consecutive days they have been active,
              and how long it has been since their last run and last long run.
            - Think in sequence, not in isolation. Today's session should make sense as the next one
              after what came before, and should not compromise what plausibly comes next. A single
              ambitious session is worth less than a week of consistent, repeatable training.
            - Use the athlete's measured thresholds (lactate threshold heart rate and threshold
              pace) when they are present. Derive concrete targets from them rather than quoting
              generic zones.
            - Respect the stated environment. A treadmill session needs speeds and inclines that can
              actually be dialled in; an outdoor session does not.
            - Respect the available time as a hard limit. If the time available does not fit the
              session you would otherwise prescribe, redesign the session so it fits and keeps as
              much of its training purpose as possible. Do not exceed the stated time.

            How to handle missing and sensitive information:
            - A null value means the data genuinely does not exist. Treat it as unknown. Never
              estimate, assume or invent a recovery metric, a threshold, or a past session that is
              not in the context you were given, and never let a missing metric read as a good one.
            - If recovery data is entirely unavailable, say so plainly in your recovery assessment
              and design conservatively rather than optimistically.
            - If quality-session detection is reported as unavailable, do not conclude that the
              athlete has done no quality work; state the uncertainty instead.
            - If the athlete reports pain, injury, illness or unusual fatigue, that outranks every
              training goal in the context, including an explicit request for a harder session.
              Never ignore it, never work around it, and name it in your warnings.

            Your rationale is written for the athlete to read: two or three plain sentences saying
            what you chose and why. Do not include internal reasoning, step-by-step deliberation, or
            system details. Keep it short and concrete.
        """.trimIndent()

        val RESPONSE_CONTRACT = """
            RESPOND WITH A SINGLE JSON OBJECT AND NOTHING ELSE.
            No markdown code fences. No explanation before or after. No trailing commentary.

            Shape (every field is required unless marked optional):
            {
              "assessment": {
                "recovery": "short phrase describing recovery readiness, or that it is unknown",
                "loadTrend": "short phrase describing the recent training load",
                "selectedWorkoutType": "EASY | RECOVERY | LONG | THRESHOLD | INTERVAL | TEMPO | REST | CROSS_TRAINING",
                "rationale": "2-3 plain sentences for the athlete",
                "warnings": ["optional short strings; omit or leave empty when there are none"]
              },
              "workout": {
                "title": "short session name",
                "totalDurationMinutes": 45,
                "segments": [
                  {
                    "type": "WARM_UP | MAIN | COOL_DOWN | REST",
                    "durationMinutes": 10,
                    "intensity": "NONE | VERY_EASY | EASY | MODERATE | HARD",
                    "description": "optional one-line instruction",
                    "paceSecondsPerKmFast": 330,
                    "paceSecondsPerKmSlow": 360,
                    "heartRateBpmMin": 135,
                    "heartRateBpmMax": 150,
                    "treadmillSpeedKphMin": 9.5,
                    "treadmillSpeedKphMax": 10.5,
                    "inclinePercentMin": 0.5,
                    "inclinePercentMax": 1.0,
                    "repetitions": 5,
                    "recoveryDurationMinutes": 2
                  }
                ]
              }
            }

            Rules the response must satisfy:
            - Pace is SECONDS PER KILOMETRE, so the "fast" value is the SMALLER number.
            - Every target field is optional: omit it or use null when you are not prescribing it.
              Omit pace and heart-rate targets entirely if the athlete has no measured threshold.
              Omit treadmill speed and incline unless the session is on a treadmill.
            - "repetitions" and "recoveryDurationMinutes" describe an interval block. When used,
              "durationMinutes" is the work duration of ONE repetition, and the block contributes
              repetitions * (durationMinutes + recoveryDurationMinutes) minutes to the total.
            - "totalDurationMinutes" MUST equal the sum of every segment's contribution computed
              that way. Check this before answering.
            - Use only the enum values listed above, spelled exactly as shown.
        """.trimIndent()
    }
}
