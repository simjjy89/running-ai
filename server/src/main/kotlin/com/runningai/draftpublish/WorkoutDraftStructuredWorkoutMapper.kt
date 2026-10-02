package com.runningai.draftpublish

import com.runningai.coach.WorkoutDraft
import com.runningai.coach.WorkoutDraftSegment
import com.runningai.training.CandidateTrainingType
import com.runningai.training.IntensityClass
import com.runningai.training.PaceTarget
import com.runningai.training.PrimaryTargetType
import com.runningai.training.SegmentType
import com.runningai.training.StructuredWorkout
import com.runningai.training.StructuredWorkoutStep
import com.runningai.training.TreadmillTarget
import org.springframework.stereotype.Component

/**
 * Re-shapes an approved AI [WorkoutDraft] into the provider-neutral [StructuredWorkout] the verified
 * `IntervalsWorkoutRenderer` consumes. **A transport mapping, never a training decision**: every
 * duration, pace, treadmill speed and incline is copied exactly, in the draft's order, and nothing is
 * recomputed from the athlete profile (unlike the legacy `StructuredWorkoutMapper`, which re-shapes a
 * freshly *computed* deterministic prescription and is therefore never used for drafts).
 *
 * Only drafts that passed [ApprovedWorkoutDraftPublishabilityValidator.problems] may be mapped: that
 * validator rejects everything this mapper could not carry over losslessly (absolute-bpm heart
 * rate, half-specified ranges, a treadmill speed without an incline, ...), so the mapper itself
 * never has to drop or invent a value.
 *
 * Repeats: the structured form has no repeat block, so `repetitions = 5, durationMinutes = 3,
 * recoveryDurationMinutes = 2` is expanded into 5 x (3-minute work step + 2-minute recovery step).
 * This is the draft's own arithmetic (`effectiveDurationMinutes` counts the recovery after the last
 * repetition too), so the total is preserved exactly; the validator re-checks the sum after mapping.
 */
@Component
class WorkoutDraftStructuredWorkoutMapper {

    fun map(draft: WorkoutDraft): StructuredWorkout {
        require(!draft.isRest) { "A REST draft is never mapped to a structured workout" }
        val intent = requireNotNull(intentOf(draft.workoutType)) {
            "workoutType ${draft.workoutType} has no structured-workout representation"
        }
        val steps = draft.segments.flatMap(::expand)
        return StructuredWorkout(draft.date, intent, draft.totalDurationMinutes, steps)
    }

    private fun expand(segment: WorkoutDraftSegment): List<StructuredWorkoutStep> {
        val work = workStep(segment)
        val repetitions = segment.repetitions ?: 1
        val recoveryMinutes = segment.recoveryDurationMinutes ?: 0
        val recovery = if (recoveryMinutes > 0) recoveryStep(recoveryMinutes) else null
        return (1..repetitions).flatMap { listOfNotNull(work, recovery) }
    }

    private fun workStep(s: WorkoutDraftSegment): StructuredWorkoutStep {
        val pace = if (s.paceSecondsPerKmFast != null && s.paceSecondsPerKmSlow != null) {
            PaceTarget(s.paceSecondsPerKmFast, s.paceSecondsPerKmSlow)
        } else {
            null
        }
        val treadmill = if (s.inclinePercentMin != null && s.inclinePercentMax != null) {
            TreadmillTarget(s.treadmillSpeedKphMin, s.treadmillSpeedKphMax, s.inclinePercentMin, s.inclinePercentMax)
        } else {
            null
        }
        val primary = when {
            pace != null -> PrimaryTargetType.PACE
            s.type == SegmentType.REST -> PrimaryTargetType.NONE
            else -> PrimaryTargetType.QUALITATIVE
        }
        return StructuredWorkoutStep(
            s.type, s.durationMinutes, s.intensity, s.description, primary, pace, null, treadmill,
        )
    }

    /**
     * The recovery between repetitions. The draft gives it a duration only (no intensity, no
     * target), so it carries none: label "Rest", no target, nothing invented.
     */
    private fun recoveryStep(minutes: Int) = StructuredWorkoutStep(
        SegmentType.REST, minutes, IntensityClass.NONE, RECOVERY_DESCRIPTION, PrimaryTargetType.NONE, null, null, null,
    )

    companion object {
        const val RECOVERY_DESCRIPTION = "Recovery between repetitions"

        /**
         * The structured workout's `intent` label for a coach workout type (the coach's own response
         * contract: EASY, RECOVERY, LONG, THRESHOLD, INTERVAL, TEMPO, REST, CROSS_TRAINING). The
         * renderer only uses the intent to recognise REST, so this is a label, not a re-decision.
         *
         * Returns null (unpublishable) for REST (never published), for CROSS_TRAINING (the publisher
         * always creates an Intervals *Run* event, which would misstate a non-running session) and for
         * any unknown type.
         */
        fun intentOf(workoutType: String): CandidateTrainingType? = when (workoutType) {
            "EASY" -> CandidateTrainingType.EASY
            "RECOVERY" -> CandidateTrainingType.RECOVERY
            "LONG" -> CandidateTrainingType.LONG
            "THRESHOLD", "INTERVAL", "TEMPO" -> CandidateTrainingType.QUALITY
            else -> null
        }
    }
}
