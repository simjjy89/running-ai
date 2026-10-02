package com.runningai.draftpublish

import com.runningai.coach.WorkoutDraft
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
 * Heart-rate policy (Phase 6G, option B): a draft carries absolute bpm, while the renderer's only
 * heart-rate form is whole-percent `%LTHR` (absolute-bpm Intervals tokens have no verified legacy
 * evidence). Converting would round the approved values and depend on the *current* LTHR rather than
 * on what was approved, and with a pace target present the renderer would show only the pace. So any
 * segment with a heart-rate target is unpublishable; the target is never silently dropped or converted.
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

        if (s.heartRateBpmMin != null || s.heartRateBpmMax != null) {
            p += "$at has a heart-rate target in bpm, which the Intervals renderer cannot represent " +
                "without converting it (only %LTHR is supported); it is not published rather than dropped"
        }

        val pace = bothOrNeither(at, "pace", s.paceSecondsPerKmFast, s.paceSecondsPerKmSlow, p)
        if (pace) {
            listOf(s.paceSecondsPerKmFast!!, s.paceSecondsPerKmSlow!!).forEach {
                if (it <= 0 || it >= MAX_PACE_SECONDS_PER_KM) p += "$at pace ${it}s/km cannot be rendered"
            }
            if (s.paceSecondsPerKmFast!! > s.paceSecondsPerKmSlow!!) p += "$at pace range is inverted"
        }

        val speed = bothOrNeither(at, "treadmill speed", s.treadmillSpeedKphMin, s.treadmillSpeedKphMax, p)
        val incline = bothOrNeither(at, "incline", s.inclinePercentMin, s.inclinePercentMax, p)
        if (speed && s.inclinePercentMin == null && s.inclinePercentMax == null) {
            p += "$at has a treadmill speed but no incline; the Garmin-safe cue needs both and an incline " +
                "is never invented"
        }
        if (speed) {
            listOf(s.treadmillSpeedKphMin!!, s.treadmillSpeedKphMax!!).forEach {
                if (!it.isFinite() || it <= 0) p += "$at treadmill speed $it cannot be rendered"
            }
        }
        if (incline) {
            listOf(s.inclinePercentMin!!, s.inclinePercentMax!!).forEach {
                if (!it.isFinite() || it < 0) p += "$at incline $it% cannot be rendered (negative incline is not supported)"
            }
        }

        // The renderer emits no intensity label or description, only label/cue/duration/target. A hard or
        // moderate block with no numeric target would reach the watch as an unlabelled "Main 3m".
        if ((s.intensity == IntensityClass.HARD || s.intensity == IntensityClass.MODERATE) && !pace && !speed) {
            p += "$at is ${s.intensity} but has no pace or treadmill-speed target, so its intensity " +
                "could not be shown in the published workout"
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
