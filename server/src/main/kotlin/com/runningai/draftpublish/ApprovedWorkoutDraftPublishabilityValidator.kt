package com.runningai.draftpublish

import com.runningai.coach.WorkoutDraft
import com.runningai.coach.WorkoutDraftRecovery
import com.runningai.coach.WorkoutDraftSegment
import com.runningai.training.IntensityClass
import com.runningai.training.StructuredWorkout
import org.springframework.stereotype.Component

/**
 * Decides whether an approved draft can be published **without changing what the athlete approved**.
 * Approval and publishability are separate: an athlete may approve a draft that the verified
 * Intervals renderer cannot express, and then publishing it is refused (fail closed) rather than
 * quietly dropping or converting part of it. Every check runs before any publisher or client call.
 *
 * It never judges the training itself (how hard, how long): those are the coach's decisions and were
 * already hard-safety validated when the draft was created.
 *
 * Heart-rate policy (Phase 6G, option B, unchanged by 6H-7.1): a segment with an **absolute bpm**
 * target is unpublishable. The renderer's only heart-rate form is whole-percent `%LTHR`, and
 * converting bpm to percent here would depend on whatever LTHR happens to be on file at publish
 * time rather than on what the athlete actually approved - so it is never converted, only refused.
 * A **%LTHR** target (Phase 6H-7.1) is the opposite case: it is exactly what the renderer already
 * emits, so it is accepted and carried through unchanged by `WorkoutDraftStructuredWorkoutMapper`.
 */
@Component
class ApprovedWorkoutDraftPublishabilityValidator {

    /** Problems found on the draft itself, before mapping. Empty = mappable. Never called for REST. */
    fun problems(draft: WorkoutDraft): List<String> {
        require(!draft.isRest) { "A REST draft is never published as a workout" }
        val p = mutableListOf<String>()
        if (WorkoutDraftStructuredWorkoutMapper.intentOf(draft.workoutType) == null) {
            p += "workoutType ${draft.workoutType} cannot be published as an Intervals.icu run workout"
        }
        if (draft.totalDurationMinutes <= 0) {
            p += "totalDurationMinutes must be > 0 but was ${draft.totalDurationMinutes}"
        }
        if (draft.segments.isEmpty()) {
            p += "the workout has no segments"
        }
        draft.segments.forEachIndexed { i, s -> segmentProblems("segment $i", s, p) }
        return p
    }

    /** Problems found after mapping: the structure must hold exactly the approved total duration. */
    fun mappedProblems(draft: WorkoutDraft, workout: StructuredWorkout): List<String> {
        val p = mutableListOf<String>()
        if (workout.steps().isEmpty()) {
            p += "the mapped workout has no steps"
        }
        workout.steps().forEachIndexed { i, step ->
            if (step.durationMinutes() <= 0) p += "mapped step $i has a non-positive duration"
        }
        val summed = workout.steps().sumOf { it.durationMinutes() }
        if (summed != draft.totalDurationMinutes) {
            p += "mapped steps sum to $summed minutes but the approved draft totals ${draft.totalDurationMinutes}"
        }
        return p
    }

    private fun segmentProblems(at: String, s: WorkoutDraftSegment, p: MutableList<String>) {
        if (s.durationMinutes <= 0) p += "$at duration must be > 0"
        s.repetitions?.let { if (it <= 0) p += "$at repetitions must be > 0" }
        s.recoveryDurationMinutes?.let { if (it < 0) p += "$at recoveryDurationMinutes must be >= 0" }
        if (s.recovery != null && s.recoveryDurationMinutes != null) {
            p += "$at has both a recovery object and the legacy recoveryDurationMinutes; " +
                "exactly one recovery representation is allowed"
        }

        if (s.heartRateBpmMin != null || s.heartRateBpmMax != null) {
            p += "$at has a heart-rate target in bpm, which the Intervals renderer cannot represent " +
                "without converting it (only %LTHR is supported); it is not published rather than dropped"
        }
        if ((s.heartRateBpmMin != null || s.heartRateBpmMax != null) &&
            (s.heartRatePercentLthrMin != null || s.heartRatePercentLthrMax != null)
        ) {
            p += "$at has both an absolute bpm target and a %LTHR target; exactly one heart-rate " +
                "representation is allowed, never silently chosen between"
        }

        val pace = bothOrNeither(at, "pace", s.paceSecondsPerKmFast, s.paceSecondsPerKmSlow, p)
        if (pace) checkPaceValues(at, s.paceSecondsPerKmFast!!, s.paceSecondsPerKmSlow!!, p)

        val heartRate = bothOrNeither(at, "%LTHR", s.heartRatePercentLthrMin, s.heartRatePercentLthrMax, p)
        if (heartRate) checkPercentLthrValues(at, s.heartRatePercentLthrMin!!, s.heartRatePercentLthrMax!!, p)

        val speed = bothOrNeither(at, "treadmill speed", s.treadmillSpeedKphMin, s.treadmillSpeedKphMax, p)
        val incline = bothOrNeither(at, "incline", s.inclinePercentMin, s.inclinePercentMax, p)
        if (speed && s.inclinePercentMin == null && s.inclinePercentMax == null) {
            p += "$at has a treadmill speed but no incline; the Garmin-safe cue needs both and an incline " +
                "is never invented"
        }
        if (speed) checkSpeedValues(at, s.treadmillSpeedKphMin!!, s.treadmillSpeedKphMax!!, p)
        if (incline) checkInclineValues(at, s.inclinePercentMin!!, s.inclinePercentMax!!, p)

        // The renderer emits no intensity label or description, only label/cue/duration/target. A hard or
        // moderate block with no numeric target would reach the watch as an unlabelled "Main 3m".
        if ((s.intensity == IntensityClass.HARD || s.intensity == IntensityClass.MODERATE) && !pace && !heartRate && !speed) {
            p += "$at is ${s.intensity} but has no pace, %LTHR or treadmill-speed target, so its intensity " +
                "could not be shown in the published workout"
        }

        s.recovery?.let { recoveryProblems("$at.recovery", it, p) }
    }

    private fun recoveryProblems(at: String, r: WorkoutDraftRecovery, p: MutableList<String>) {
        if (r.durationMinutes <= 0) p += "$at duration must be > 0"

        val pace = bothOrNeither(at, "pace", r.paceSecondsPerKmFast, r.paceSecondsPerKmSlow, p)
        if (pace) checkPaceValues(at, r.paceSecondsPerKmFast!!, r.paceSecondsPerKmSlow!!, p)

        val heartRate = bothOrNeither(at, "%LTHR", r.heartRatePercentLthrMin, r.heartRatePercentLthrMax, p)
        if (heartRate) checkPercentLthrValues(at, r.heartRatePercentLthrMin!!, r.heartRatePercentLthrMax!!, p)

        val speed = bothOrNeither(at, "treadmill speed", r.treadmillSpeedKphMin, r.treadmillSpeedKphMax, p)
        val incline = bothOrNeither(at, "incline", r.inclinePercentMin, r.inclinePercentMax, p)
        if (speed && r.inclinePercentMin == null && r.inclinePercentMax == null) {
            p += "$at has a treadmill speed but no incline; the Garmin-safe cue needs both and an incline " +
                "is never invented"
        }
        if (speed) checkSpeedValues(at, r.treadmillSpeedKphMin!!, r.treadmillSpeedKphMax!!, p)
        if (incline) checkInclineValues(at, r.inclinePercentMin!!, r.inclinePercentMax!!, p)

        if ((r.intensity == IntensityClass.HARD || r.intensity == IntensityClass.MODERATE) && !pace && !heartRate && !speed) {
            p += "$at is ${r.intensity} but has no pace, %LTHR or treadmill-speed target, so its intensity " +
                "could not be shown in the published workout"
        }
    }

    private fun checkPaceValues(at: String, fast: Int, slow: Int, p: MutableList<String>) {
        listOf(fast, slow).forEach { if (it <= 0 || it >= MAX_PACE_SECONDS_PER_KM) p += "$at pace ${it}s/km cannot be rendered" }
        if (fast > slow) p += "$at pace range is inverted"
    }

    private fun checkPercentLthrValues(at: String, min: Int, max: Int, p: MutableList<String>) {
        listOf(min, max).forEach { if (it <= 0) p += "$at %LTHR $it% cannot be rendered" }
        if (min > max) p += "$at %LTHR range is inverted"
    }

    private fun checkSpeedValues(at: String, min: Double, max: Double, p: MutableList<String>) {
        listOf(min, max).forEach { if (!it.isFinite() || it <= 0) p += "$at treadmill speed $it cannot be rendered" }
    }

    private fun checkInclineValues(at: String, min: Double, max: Double, p: MutableList<String>) {
        listOf(min, max).forEach {
            if (!it.isFinite() || it < 0) p += "$at incline $it% cannot be rendered (negative incline is not supported)"
        }
    }

    /** True when both bounds are present; records a problem when exactly one is. */
    private fun bothOrNeither(at: String, label: String, lower: Any?, upper: Any?, p: MutableList<String>): Boolean {
        if ((lower == null) != (upper == null)) {
            p += "$at has only one bound of its $label range; a half range cannot be rendered without inventing the other"
        }
        return lower != null && upper != null
    }

    private companion object {
        /** The renderer's pace token range (exclusive). */
        const val MAX_PACE_SECONDS_PER_KM = 3600
    }
}
