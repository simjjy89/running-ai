package com.runningai.draftpublish

import com.runningai.coach.WorkoutDraft
import com.runningai.coach.WorkoutDraftRecovery
import com.runningai.coach.WorkoutDraftSegment
import com.runningai.training.CandidateTrainingType
import com.runningai.training.HeartRateTarget
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
 * duration, pace, %LTHR, treadmill speed and incline is copied exactly, in the draft's order, and
 * nothing is recomputed from the athlete profile (unlike the legacy `StructuredWorkoutMapper`, which
 * re-shapes a freshly *computed* deterministic prescription and is therefore never used for drafts).
 * No athlete-profile lookup, no current-LTHR lookup and no target recalculation happen here (Phase
 * 6H-7.1 §54): a `%LTHR` target is carried over as the exact percent the athlete approved.
 *
 * Only drafts that passed [ApprovedWorkoutDraftPublishabilityValidator.problems] may be mapped: that
 * validator rejects everything this mapper could not carry over losslessly (absolute-bpm heart
 * rate, half-specified ranges, a treadmill speed without an incline, ...), so the mapper itself
 * never has to drop or invent a value.
 *
 * Repeats: the structured form has no repeat block, so `repetitions = 5, durationMinutes = 3,
 * recovery = ...` (or the legacy `recoveryDurationMinutes = 2`) is expanded into 5 x (3-minute work
 * step + a recovery step). This is the draft's own arithmetic (`effectiveDurationMinutes` counts the
 * recovery after the last repetition too), so the total is preserved exactly; the validator
 * re-checks the sum after mapping.
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
        val recovery = segment.recovery?.let(::recoveryStepFrom)
            ?: segment.recoveryDurationMinutes?.takeIf { it > 0 }?.let(::legacyRecoveryStep)
        return (1..repetitions).flatMap { listOfNotNull(work, recovery) }
    }

    private fun workStep(s: WorkoutDraftSegment): StructuredWorkoutStep {
        val pace = paceOf(s.paceSecondsPerKmFast, s.paceSecondsPerKmSlow)
        val heartRate = percentLthrOf(s.heartRatePercentLthrMin, s.heartRatePercentLthrMax)
        val treadmill = treadmillOf(s.treadmillSpeedKphMin, s.treadmillSpeedKphMax, s.inclinePercentMin, s.inclinePercentMax)
        val primary = s.primaryTargetType ?: legacyPrimary(s.type, pace)
        return StructuredWorkoutStep(
            s.type, s.durationMinutes, s.intensity, s.description, primary,
            pace.takeIf { primary == PrimaryTargetType.PACE },
            heartRate.takeIf { primary == PrimaryTargetType.HEART_RATE },
            treadmill,
        )
    }

    /** The targeted replacement for [legacyRecoveryStep]: carries the recovery's own device target. */
    private fun recoveryStepFrom(r: WorkoutDraftRecovery): StructuredWorkoutStep {
        val pace = paceOf(r.paceSecondsPerKmFast, r.paceSecondsPerKmSlow)
        val heartRate = percentLthrOf(r.heartRatePercentLthrMin, r.heartRatePercentLthrMax)
        val treadmill = treadmillOf(r.treadmillSpeedKphMin, r.treadmillSpeedKphMax, r.inclinePercentMin, r.inclinePercentMax)
        return StructuredWorkoutStep(
            SegmentType.REST, r.durationMinutes, r.intensity, r.description ?: RECOVERY_DESCRIPTION,
            r.primaryTargetType,
            pace.takeIf { r.primaryTargetType == PrimaryTargetType.PACE },
            heartRate.takeIf { r.primaryTargetType == PrimaryTargetType.HEART_RATE },
            treadmill,
        )
    }

    /**
     * The legacy recovery between repetitions (pre-6H-7.1 drafts, e.g. the already-published
     * Draft #7): a duration only, no intensity, no target - label "Rest", nothing invented.
     */
    private fun legacyRecoveryStep(minutes: Int) = StructuredWorkoutStep(
        SegmentType.REST, minutes, IntensityClass.NONE, RECOVERY_DESCRIPTION, PrimaryTargetType.NONE, null, null, null,
    )

    private fun paceOf(fast: Int?, slow: Int?): PaceTarget? =
        if (fast != null && slow != null) PaceTarget(fast, slow) else null

    /** A draft only ever carries the percent; bpm is left null (never recomputed from a profile). */
    private fun percentLthrOf(minPercent: Int?, maxPercent: Int?): HeartRateTarget? =
        if (minPercent != null && maxPercent != null) HeartRateTarget(minPercent, maxPercent, null, null) else null

    private fun treadmillOf(speedMin: Double?, speedMax: Double?, inclineMin: Double?, inclineMax: Double?): TreadmillTarget? =
        if (inclineMin != null && inclineMax != null) TreadmillTarget(speedMin, speedMax, inclineMin, inclineMax) else null

    /** Pre-6H-7.1 inference, preserved exactly for a draft that never set `primaryTargetType`. */
    private fun legacyPrimary(type: SegmentType, pace: PaceTarget?): PrimaryTargetType = when {
        pace != null -> PrimaryTargetType.PACE
        type == SegmentType.REST -> PrimaryTargetType.NONE
        else -> PrimaryTargetType.QUALITATIVE
    }

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
