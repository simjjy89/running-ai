package com.runningai.coach

import org.springframework.stereotype.Component
import java.time.LocalDate
import kotlin.math.abs

/** A draft that failed hard-safety validation. [violations] are short, user-safe reason strings. */
class WorkoutDraftValidationException(val violations: List<String>) :
    RuntimeException("Workout draft failed validation: ${violations.joinToString("; ")}")

/**
 * Hard-safety gate on what the AI coach returned. It **accepts or rejects — it never rewrites**.
 *
 * The distinction this class is built around:
 *  - *hard safety* (enforced here): structurally impossible or physically nonsensical output —
 *    a negative duration, an inverted range, a NaN, a pace an order of magnitude away from the
 *    athlete's own threshold, a date that is not the date that was asked for. These indicate a
 *    malformed response or a unit error, never a coaching opinion.
 *  - *coaching policy* (deliberately NOT enforced here): whether today should be easy or hard, how
 *    long the long run should be, whether a threshold session two days after the last one is wise.
 *    Those are the coach's decisions; re-deciding them in Spring would recreate exactly the
 *    rule-engine this phase exists to replace.
 *
 * Plausibility bands are therefore wide (see [CoachProperties.Validation]) and only ever applied
 * relative to the athlete's own measured threshold, never to invented population norms.
 */
@Component
class WorkoutDraftValidator(private val properties: CoachProperties) {

    fun validate(draft: WorkoutDraft, expectedDate: LocalDate, athlete: AthleteThresholds) {
        val v = mutableListOf<String>()
        val limits = properties.validation

        if (draft.date != expectedDate) {
            v += "date ${draft.date} does not match the requested date $expectedDate"
        }
        if (draft.title.isBlank()) {
            v += "title is blank"
        }
        if (draft.workoutType.isBlank()) {
            v += "workoutType is blank"
        }
        if (draft.assessment.rationale.isBlank()) {
            v += "assessment rationale is blank"
        }
        if (draft.assessment.selectedWorkoutType.isBlank()) {
            v += "assessment selectedWorkoutType is blank"
        }
        if (draft.segments.isEmpty()) {
            v += "workout has no segments"
        }
        if (draft.totalDurationMinutes <= 0) {
            v += "totalDurationMinutes must be > 0 but was ${draft.totalDurationMinutes}"
        }
        if (draft.totalDurationMinutes > limits.maxTotalDurationMinutes) {
            v += "totalDurationMinutes ${draft.totalDurationMinutes} exceeds the safety ceiling " +
                "of ${limits.maxTotalDurationMinutes}"
        }
        if (draft.segments.isNotEmpty() && draft.totalDurationMinutes < limits.minTotalDurationMinutes) {
            v += "totalDurationMinutes ${draft.totalDurationMinutes} is below the minimum " +
                "of ${limits.minTotalDurationMinutes}"
        }

        draft.segments.forEachIndexed { i, s -> validateSegment(i, s, athlete, v) }

        if (draft.segments.isNotEmpty() && v.none { it.startsWith("segment") }) {
            val summed = draft.segments.sumOf { it.effectiveDurationMinutes }
            if (summed != draft.totalDurationMinutes) {
                v += "segment durations sum to $summed minutes but totalDurationMinutes is " +
                    "${draft.totalDurationMinutes}"
            }
        }

        if (v.isNotEmpty()) {
            throw WorkoutDraftValidationException(v)
        }
    }

    private fun validateSegment(
        index: Int,
        s: WorkoutDraftSegment,
        athlete: AthleteThresholds,
        v: MutableList<String>,
    ) {
        val at = "segment $index"

        if (s.durationMinutes <= 0) {
            v += "$at duration must be > 0 but was ${s.durationMinutes}"
        }
        s.repetitions?.let {
            if (it <= 0) v += "$at repetitions must be > 0 but was $it"
        }
        s.recoveryDurationMinutes?.let {
            if (it < 0) v += "$at recoveryDurationMinutes must be >= 0 but was $it"
        }

        validateRange(at, "pace (seconds/km)", s.paceSecondsPerKmFast, s.paceSecondsPerKmSlow, v)
        validateRange(at, "heart rate (bpm)", s.heartRateBpmMin, s.heartRateBpmMax, v)
        validateDoubleRange(at, "treadmill speed (kph)", s.treadmillSpeedKphMin, s.treadmillSpeedKphMax, v)

        // Incline may legitimately be 0 or negative (downhill), so it is only range- and
        // finiteness-checked, plus a sanity bound no real treadmill exceeds.
        listOfNotNull(s.inclinePercentMin, s.inclinePercentMax).forEach {
            if (!it.isFinite()) v += "$at incline is not a finite number"
            else if (abs(it) > MAX_ABS_INCLINE_PERCENT) v += "$at incline $it% is out of range"
        }
        if (s.inclinePercentMin != null && s.inclinePercentMax != null &&
            s.inclinePercentMin.isFinite() && s.inclinePercentMax.isFinite() &&
            s.inclinePercentMin > s.inclinePercentMax
        ) {
            v += "$at incline range is inverted (${s.inclinePercentMin} > ${s.inclinePercentMax})"
        }

        // Pace is seconds per km, so the FAST bound is the SMALLER number.
        if (s.paceSecondsPerKmFast != null && s.paceSecondsPerKmSlow != null &&
            s.paceSecondsPerKmFast > s.paceSecondsPerKmSlow
        ) {
            v += "$at pace range is inverted (fast ${s.paceSecondsPerKmFast}s/km is slower than " +
                "slow ${s.paceSecondsPerKmSlow}s/km)"
        }

        athlete.lactateThresholdPaceSecondsPerKm?.let { threshold ->
            val tolerance = properties.validation.pacePlausibilityTolerance
            listOfNotNull(s.paceSecondsPerKmFast, s.paceSecondsPerKmSlow).forEach { pace ->
                if (pace < threshold * (1 - tolerance) || pace > threshold / (1 - tolerance)) {
                    v += "$at pace ${pace}s/km is implausible against the athlete's threshold " +
                        "pace of ${threshold}s/km"
                }
            }
        }
        athlete.lactateThresholdHeartRateBpm?.let { lthr ->
            val tolerance = properties.validation.heartRatePlausibilityTolerance
            listOfNotNull(s.heartRateBpmMin, s.heartRateBpmMax).forEach { bpm ->
                if (bpm < lthr * (1 - tolerance) || bpm > lthr * (1 + tolerance)) {
                    v += "$at heart rate ${bpm}bpm is implausible against the athlete's LTHR of ${lthr}bpm"
                }
            }
        }
    }

    private fun validateRange(at: String, label: String, lower: Int?, upper: Int?, v: MutableList<String>) {
        listOfNotNull(lower, upper).forEach {
            if (it <= 0) v += "$at $label must be > 0 but was $it"
        }
        if (lower != null && upper != null && lower > upper && label.startsWith("heart")) {
            v += "$at $label range is inverted ($lower > $upper)"
        }
    }

    private fun validateDoubleRange(at: String, label: String, lower: Double?, upper: Double?, v: MutableList<String>) {
        listOfNotNull(lower, upper).forEach {
            if (!it.isFinite()) v += "$at $label is not a finite number"
            else if (it <= 0) v += "$at $label must be > 0 but was $it"
        }
        if (lower != null && upper != null && lower.isFinite() && upper.isFinite() && lower > upper) {
            v += "$at $label range is inverted ($lower > $upper)"
        }
    }

    private companion object {
        /** No treadmill or real hill session goes beyond this; purely a nonsense filter. */
        const val MAX_ABS_INCLINE_PERCENT = 40.0
    }
}
