package com.runningai.coach

import com.fasterxml.jackson.annotation.JsonIgnore
import com.runningai.training.IntensityClass
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
 * [repetitions] and [recoveryDurationMinutes] describe an interval block without needing a separate
 * nested structure: `repetitions = 5, durationMinutes = 3, recoveryDurationMinutes = 2` is
 * "5 x 3min with 2min recovery", and [durationMinutes] is then the work duration of ONE repetition.
 */
data class WorkoutDraftSegment(
    val type: SegmentType,
    val durationMinutes: Int,
    val intensity: IntensityClass,
    val description: String? = null,
    val paceSecondsPerKmFast: Int? = null,
    val paceSecondsPerKmSlow: Int? = null,
    val heartRateBpmMin: Int? = null,
    val heartRateBpmMax: Int? = null,
    val treadmillSpeedKphMin: Double? = null,
    val treadmillSpeedKphMax: Double? = null,
    val inclinePercentMin: Double? = null,
    val inclinePercentMax: Double? = null,
    val repetitions: Int? = null,
    val recoveryDurationMinutes: Int? = null,
) {
    /**
     * Minutes this segment contributes to the workout total: for a repeated block, every repetition
     * plus the recovery between them (the recovery after the final repetition counts too, which is
     * how a coach normally writes such a block).
     *
     * Derived, so it is never serialized: segments are persisted as JSON and read back, and an
     * emitted computed field would fail to deserialize into the constructor.
     */
    @get:JsonIgnore
    val effectiveDurationMinutes: Int
        get() {
            val reps = repetitions ?: 1
            val recovery = recoveryDurationMinutes ?: 0
            return reps * durationMinutes + reps * recovery
        }
}

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
