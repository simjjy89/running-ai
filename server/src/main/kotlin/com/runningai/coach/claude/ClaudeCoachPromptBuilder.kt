package com.runningai.coach.claude

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.runningai.coach.CoachTrainingContext
import com.runningai.coach.TrainingContext
import com.runningai.coach.TrainingContextV2
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

    /** The initial "design today's session" message. Dispatches on the context shape (V1 or V2). */
    fun createPrompt(context: CoachTrainingContext): String = buildString {
        appendLine("Design this athlete's training session for ${context.date}.")
        appendLine()
        appendLine(contextLabel(context) + " (authoritative; a null value means the data is genuinely unavailable):")
        appendLine(snapshot(context))
        appendLine()
        appendLine(RESPONSE_CONTRACT)
        targetGuidance(context)?.let { appendLine(); appendLine(it) }
    }

    /** The "the athlete asked for a change, design it again" message. */
    fun revisePrompt(context: CoachTrainingContext, currentDraft: WorkoutDraft, userRequest: String): String =
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
            appendLine(contextLabel(context) + " (unchanged; a null value means the data is genuinely unavailable):")
            appendLine(snapshot(context))
            appendLine()
            appendLine(RESPONSE_CONTRACT)
            targetGuidance(context)?.let { appendLine(); appendLine(it) }
        }

    /** The deterministic JSON snapshot handed to the model, for either context shape. */
    fun snapshot(context: CoachTrainingContext): String = mapper.writeValueAsString(context)

    private fun contextLabel(context: CoachTrainingContext): String = when (context) {
        is TrainingContextV2 -> "TRAINING CONTEXT V2 (evidence: Garmin detail, RunningAI Analysis, " +
            "Intervals.icu training model, Garmin recovery)"
        is TrainingContext -> "TRAINING CONTEXT"
        else -> "TRAINING CONTEXT"
    }

    /**
     * V2-only device-target guidance (Phase 6H-7.1): `null` for V1, so V1's existing behaviour and
     * every V1 eval scenario are unaffected. Only emitted when the athlete actually has a threshold
     * to derive a target from - with neither on file, `QUALITATIVE` stays the right answer and no
     * guidance about which one to use is relevant.
     */
    private fun targetGuidance(context: CoachTrainingContext): String? {
        if (context !is TrainingContextV2) return null
        val hasLthr = context.athlete.lactateThresholdHeartRateBpm != null
        val hasPace = context.athlete.lactateThresholdPaceSecondsPerKm != null
        if (!hasLthr && !hasPace) return null
        return TARGET_GUIDANCE
    }

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

            How to read the recovery data (from the athlete's Garmin watch):
            - 'recovery' holds HRV, sleep, resting heart rate, Body Battery and stress. Each metric
              gives its latest value ('current'), the athlete's own average over the previous
              'baselineWindowDays' days ('baseline'), 'difference' (current minus baseline) and
              'differencePercent'. These are plain measurements. The software has deliberately NOT
              rated them as good or bad and has NOT adjusted the session for them: interpreting
              them, and deciding what they mean for today, is your job.
            - 'ageDays' is how many days before the session date the value was recorded. 0 means
              it belongs to the session date; larger numbers mean the reading is older and may no
              longer describe the athlete today. Weigh stale readings accordingly and say so.
            - 'baselineStatus': 'INSUFFICIENT_DATA' means there were fewer than 'minimumSamples'
              days of history, so no baseline comparison exists. Do not invent one.
            - 'garminHrvStatus' and 'garminWeeklyAvgMs' are Garmin's own figures, passed through.
            - For the session date itself, Body Battery and stress can be partial-day values.
            - Wearable data and what the athlete tells you can disagree. Signals can also point in
              different directions. Weigh them as a coach would and explain the call you made.
              Normal-looking wearable numbers never override reported pain, illness or fatigue.

            Provenance, when the context separates it by source (TRAINING CONTEXT V2):
            - GARMIN: the device/source measurement itself (activity facts, recovery readings).
            - RUNNING_AI (labelled 'runningAiAnalysis'): RunningAI's own deterministic evidence
              computed from stored Garmin data — for example 'speedHrDecouplingPercent' is a
              RunningAI-derived descriptive metric, not a Garmin or Intervals.icu official score.
            - INTERVALS: Intervals.icu's own external training-model enrichment. 'ctl' is Intervals'
              calculated fitness, 'atl' is its calculated fatigue, and 'derivedForm' (ctl - atl) is
              explicitly RunningAI-derived from those two Intervals numbers, not a source field.
              No source outranks another by default: weigh each for what it actually measures.
            - 'dataCoverage' reports how much evidence exists as plain counts, not a quality verdict.
              A wide history window with a small activity count is a small amount of evidence over
              a long window, not a dense training history — read the counts, do not assume density.

            Reading 'sourceFreshness' (TRAINING CONTEXT V2; Phase 6H-9):
            - Do not call a piece of evidence recent solely because its value exists. Use
              'sourceFreshness.fitnessAgeDays' / 'recoverySourceDate' / 'recoveryAgeDays' — a number
              present in the context can still describe a day several days old.
            - No recent activity and activity data may be stale are different claims, and you must
              not collapse them. This context is built from stored data only: by itself, it cannot
              tell you whether the Garmin source was just synced or has not been touched in a while.
              Normal operation runs an explicit data-refresh step immediately before you are called,
              so ordinarily you may read a null/old 'newestActivityDate' as a genuine rest streak.
              But if you are ever told explicitly (in this prompt or a surrounding message) that the
              refresh was skipped, failed, or that source freshness is unknown, do not draw a strong
              conclusion from an activity gap — say the uncertainty out loud instead of asserting a
              rest streak you cannot actually back up.

            How to handle missing and sensitive information:
            - A null value means the data genuinely does not exist. Treat it as unknown. Never
              estimate, assume or invent a recovery metric, a threshold, or a past session that is
              not in the context you were given, and never let a missing metric read as a good one.
              Only cite recovery numbers that appear in the context. The absence of a stored activity
              or recovery reading is not proof that nothing happened that day or that recovery was
              poor — it only means RunningAI has no record of it.
            - When a recent activity's 'dataQuality.sampleCompleteness' is 'FULL', its sample-derived
              evidence (half-split, decoupling, threshold-exposure seconds) rests on the complete
              stored stream. 'DOWNSAMPLED' or 'UNKNOWN' means treat that activity's sample-derived
              numbers as less certain than a FULL one, without discarding them outright.
            - If recovery data is entirely unavailable, say so plainly in your recovery assessment
              and design conservatively rather than optimistically. If only some metrics are
              missing, name what you based the assessment on and what was unavailable.
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
                    "primaryTargetType": "PACE | HEART_RATE | QUALITATIVE | NONE, optional",
                    "paceSecondsPerKmFast": 330,
                    "paceSecondsPerKmSlow": 360,
                    "heartRatePercentLthrMin": 65,
                    "heartRatePercentLthrMax": 78,
                    "treadmillSpeedKphMin": 9.5,
                    "treadmillSpeedKphMax": 10.5,
                    "inclinePercentMin": 0.5,
                    "inclinePercentMax": 1.0,
                    "repetitions": 5,
                    "recovery": {
                      "durationMinutes": 2,
                      "intensity": "VERY_EASY",
                      "primaryTargetType": "HEART_RATE | PACE | NONE | QUALITATIVE",
                      "description": "optional one-line instruction",
                      "heartRatePercentLthrMin": 65,
                      "heartRatePercentLthrMax": 75
                    }
                  }
                ]
              }
            }

            Rules the response must satisfy:
            - Pace is SECONDS PER KILOMETRE, so the "fast" value is the SMALLER number.
            - Heart-rate targets are given as a whole-percent range of the athlete's LTHR
              ("heartRatePercentLthrMin"/"heartRatePercentLthrMax"), never as absolute bpm: an
              absolute-bpm target cannot be published and is refused rather than converted, because
              converting it would depend on whatever LTHR is on file at publish time rather than on
              what was actually approved. Do not emit "heartRateBpmMin"/"heartRateBpmMax".
            - Every target field is optional: omit it or use null when you are not prescribing it.
              Omit pace and heart-rate targets entirely if the athlete has no measured threshold for
              that kind of target. Omit treadmill speed and incline unless the session is on a
              treadmill.
            - "primaryTargetType" says which single target is the one Garmin should show for this
              step. Set it to "PACE" only together with a complete pace pair, "HEART_RATE" only
              together with a complete %LTHR pair, never both a pace pair and a %LTHR pair on the
              same step. "QUALITATIVE" or "NONE" means no numeric target on this step - then omit
              both pairs. Leaving "primaryTargetType" out entirely is also fine (older behaviour:
              a pace pair present is read as PACE, otherwise the step is qualitative).
            - "repetitions" describes an interval block repeated that many times. Give its recovery
              with the nested "recovery" object (preferred - it carries its own intensity and
              target) rather than a bare recovery duration. "durationMinutes" is the work duration of
              ONE repetition, and the block contributes
              repetitions * (durationMinutes + recovery.durationMinutes) minutes to the total.
              "recovery.primaryTargetType" follows the same rule as a segment's: use "NONE" only when
              you deliberately mean passive rest (standing/walking), not as a shortcut for "I did not
              think about it" - an easy jog recovery should carry its own %LTHR or pace target.
            - "totalDurationMinutes" MUST equal the sum of every segment's contribution computed
              that way. Check this before answering.
            - Use only the enum values listed above, spelled exactly as shown.
            - REST is a valid choice, not a failure. If you select REST, the workout is a rest day:
              "totalDurationMinutes" must be 0 and "segments" must be an empty array []. Do not
              invent walking, mobility, recovery or warm-up segments to fill it; put any optional
              advice (for example light mobility) in the rationale or warnings instead.
              Every other workout type needs at least one segment and a positive total.
        """.trimIndent()

        /**
         * V2-only (Phase 6H-7.1): appended after [RESPONSE_CONTRACT] when the athlete has a
         * measured threshold the V2 context can see. The 65-78% / 75-85% LTHR figures are
         * RunningAI's own existing deterministic VERY_EASY/EASY bands
         * (`RunningIntensityTargetPolicy`), repeated here only so this guidance agrees with that
         * code rather than inventing a parallel zone scheme.
         */
        val TARGET_GUIDANCE = """
            DEVICE TARGET COMPLETENESS (this athlete has a measured threshold on file):
            - Give every warm-up, main and cool-down step - and every repeat block's recovery, unless
              you deliberately intend passive rest - a real device target: a pace pair when the
              athlete's threshold pace is known, a %LTHR pair when LTHR is known, or either when both
              are known (pick one per step; never emit both).
            - Prefer %LTHR for warm-up, cool-down and easy-effort recovery. RunningAI's own easy-pace
              heuristic uses roughly 65-78% LTHR for very easy effort and 75-85% LTHR for easy effort;
              use these as a starting reference, not a rule to copy verbatim - judge the actual session.
            - For a quality main effort (threshold/interval/tempo), choose whichever of pace or %LTHR
              better expresses the training purpose; both are valid, but express it as exactly one.
            - Never invent a threshold that is not in the context. If neither LTHR nor threshold pace
              is present, this guidance does not apply - "QUALITATIVE" is the right answer, as before.
        """.trimIndent()
    }
}
