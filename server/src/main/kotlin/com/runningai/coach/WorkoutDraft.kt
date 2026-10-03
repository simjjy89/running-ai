package com.runningai.coach

import com.fasterxml.jackson.annotation.JsonIgnore
import com.runningai.training.IntensityClass
import com.runningai.training.PrimaryTargetType
import com.runningai.training.SegmentType
import java.time.Instant
import java.time.LocalDate

/**
 * Lifecycle of a draft: `DRAFT -> SUPERSEDED` (revised) or `DRAFT -> APPROVED` (explicit athlete
 * approval, Phase 6G). Publishing is deliberately NOT a status: whether and how an approved draft
 * reached Intervals.icu is recorded separately (`workout_draft_publication`), so this enum only ever
 * says which workout the athlete accepted.
 */
enum class WorkoutDraftStatus {
    /** The current version of this draft group. */
    DRAFT,

    /** Replaced by a newer version after a revision; kept for audit, never mutated again. */
    SUPERSEDED,

    /**
     * Explicitly approved by the athlete. Immutable from here on: it cannot be revised (a changed
     * workout needs a new draft), and at most one draft per athlete and date is ever APPROVED.
     */
    APPROVED,
}

/**
 * A workout the AI coach designed, after validation. Immutable: a revision produces a new
 * [WorkoutDraft] with `version + 1` rather than mutating this one.
 *
 * [id] and [draftGroupId] are null until the draft is persisted: the coach itself does not assign
 * identity. All versions of "the same draft" share a [draftGroupId].
 */
data class WorkoutDraft(
    val id: Long? = null,
    val draftGroupId: String? = null,
    val version: Int,
    val date: LocalDate,
    val title: String,
    val workoutType: String,
    val totalDurationMinutes: Int,
    val assessment: CoachAssessment,
    val segments: List<WorkoutDraftSegment>,
    val provider: CoachProvider,
    val model: String?,
    val status: WorkoutDraftStatus = WorkoutDraftStatus.DRAFT,
    val createdAt: Instant? = null,
) {
    /**
     * A rest day prescribed by the coach (Phase 6F.1): a first-class draft with
     * `totalDurationMinutes = 0` and no segments, never a padded "5 minutes of walking". Whether
     * to rest is the coach's decision; [WorkoutDraftValidator] only checks the shape. A later
     * publish step can recognise it here and skip creating any calendar or device workout.
     */
    @get:JsonIgnore
    val isRest: Boolean
        get() = workoutType == REST_WORKOUT_TYPE

    companion object {
        /** The workout type the coach uses for a rest day (as listed in the response contract). */
        const val REST_WORKOUT_TYPE = "REST"
    }
}

/**
 * One block of the workout. Reuses the existing Java [SegmentType] and [IntensityClass] rather
 * than introducing parallel coach-only enums.
 *
 * Targets are all optional — a coach designing for an athlete with no threshold profile returns a
 * qualitative segment — but when present they are validated (see `WorkoutDraftValidator`).
 * [repetitions] plus either [recovery] or the legacy [recoveryDurationMinutes] describe an interval
 * block without needing the repeats expanded in the draft itself; [durationMinutes] is the work
 * duration of ONE repetition.
 *
 * Two recovery representations coexist (Phase 6H-7.1):
 *  - [recoveryDurationMinutes] — the original, duration-only shape (kept for V1/legacy
 *    compatibility: it is exactly what Draft #7, already approved and published, stored).
 *  - [recovery] — a full [WorkoutDraftRecovery] with its own intensity and device target, used by
 *    the current V2 coach response contract. The two are mutually exclusive on one segment
 *    (`WorkoutDraftValidator` fails closed if both are set) so a reader never has to guess which one
 *    is authoritative.
 *
 * [heartRateBpmMin]/[heartRateBpmMax] (V1, absolute bpm — never published, see
 * `ApprovedWorkoutDraftPublishabilityValidator`) and [heartRatePercentLthrMin]/
 * [heartRatePercentLthrMax] (V2, relative to LTHR — publishable) also coexist for the same reason
 * and are mutually exclusive on one segment. [primaryTargetType] is new and optional: `null` means
 * the pre-6H-7.1 inference rule still applies (a pace target present selects `PACE`; otherwise the
 * segment is qualitative) so every already-persisted draft keeps reading exactly as it always did.
 */
data class WorkoutDraftSegment(
    val type: SegmentType,
    val durationMinutes: Int,
    val intensity: IntensityClass,
    val description: String? = null,
    val primaryTargetType: PrimaryTargetType? = null,
    val paceSecondsPerKmFast: Int? = null,
    val paceSecondsPerKmSlow: Int? = null,
    val heartRateBpmMin: Int? = null,
    val heartRateBpmMax: Int? = null,
    val heartRatePercentLthrMin: Int? = null,
    val heartRatePercentLthrMax: Int? = null,
    val treadmillSpeedKphMin: Double? = null,
    val treadmillSpeedKphMax: Double? = null,
    val inclinePercentMin: Double? = null,
    val inclinePercentMax: Double? = null,
    val repetitions: Int? = null,
    val recoveryDurationMinutes: Int? = null,
    val recovery: WorkoutDraftRecovery? = null,
) {
    /**
     * Minutes this segment contributes to the workout total: for a repeated block, every repetition
     * plus the recovery between them (the recovery after the final repetition counts too, which is
     * how a coach normally writes such a block) — whichever recovery representation is present.
     *
     * Derived, so it is never serialized: segments are persisted as JSON and read back, and an
     * emitted computed field would fail to deserialize into the constructor.
     */
    @get:JsonIgnore
    val effectiveDurationMinutes: Int
        get() {
            val reps = repetitions ?: 1
            val recoveryMinutes = recovery?.durationMinutes ?: recoveryDurationMinutes ?: 0
            return reps * durationMinutes + reps * recoveryMinutes
        }
}

/**
 * A repeat block's recovery, with its own device target (Phase 6H-7.1) — the lossless replacement
 * for the legacy duration-only [WorkoutDraftSegment.recoveryDurationMinutes] on a segment that uses
 * it. [primaryTargetType] follows the same rules as a segment's: `HEART_RATE`/`PACE` require their
 * respective target pair complete; `NONE` is a deliberate passive-rest choice by the coach (no target
 * is ever invented to fill it) and `QUALITATIVE` means no numeric target applies.
 */
data class WorkoutDraftRecovery(
    val durationMinutes: Int,
    val intensity: IntensityClass,
    val primaryTargetType: PrimaryTargetType,
    val description: String? = null,
    val paceSecondsPerKmFast: Int? = null,
    val paceSecondsPerKmSlow: Int? = null,
    val heartRatePercentLthrMin: Int? = null,
    val heartRatePercentLthrMax: Int? = null,
    val treadmillSpeedKphMin: Double? = null,
    val treadmillSpeedKphMax: Double? = null,
    val inclinePercentMin: Double? = null,
    val inclinePercentMax: Double? = null,
)

/**
 * The coach's structured analysis. Stored and shown to the athlete alongside the workout, so the
 * workout is never an unexplained block of numbers.
 *
 * [rationale] is a short user-facing explanation. Internal chain-of-thought is neither requested
 * from the model nor stored.
 */
data class CoachAssessment(
    val recoveryAssessment: String,
    val loadAssessment: String,
    val selectedWorkoutType: String,
    val rationale: String,
    val warnings: List<String> = emptyList(),
)
