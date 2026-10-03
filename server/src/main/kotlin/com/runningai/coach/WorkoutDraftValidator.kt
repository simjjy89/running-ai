package com.runningai.coach

import com.runningai.training.PrimaryTargetType
import com.runningai.training.SegmentType
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
 *
 * Phase 6H-7.1 adds target-shape rules (ambiguous bpm/%LTHR, inconsistent `primaryTargetType`,
 * malformed `recovery`) that apply regardless of context version — legacy drafts never populate
 * these new fields, so they can never trigger for a pre-6H-7.1 draft — plus one rule that is
 * genuinely new policy and is therefore gated to [TrainingContextV2] only (see [validate] with a
 * [CoachTrainingContext]): a running segment must carry a real device target when the athlete has a
 * threshold the V2 context actually reports.
 */
@Component
class WorkoutDraftValidator(private val properties: CoachProperties) {

    /** The original two-argument form: hard-safety rules only, no V2 target-completeness rule. */
    fun validate(draft: WorkoutDraft, expectedDate: LocalDate, athlete: AthleteThresholds) {
        validate(draft, expectedDate, athlete, context = null)
    }

    /**
     * Same hard-safety rules, plus (only when [context] is a [TrainingContextV2]) the target-
     * completeness rule: a non-REST, non-CROSS_TRAINING segment must have a real device target when
     * the athlete has a threshold V2 can see. V1 never gets this rule, so its existing behaviour -
     * and every existing V1 test - is unaffected.
     */
    fun validate(draft: WorkoutDraft, context: CoachTrainingContext) {
        validate(draft, context.date, context.athlete, context)
    }

    private fun validate(draft: WorkoutDraft, expectedDate: LocalDate, athlete: AthleteThresholds, context: CoachTrainingContext?) {
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
        // Shape only, never the decision: a REST draft is a rest day (0 minutes, no segments); any
        // other type is a session with segments. Whether today should be a rest day is the coach's call.
        if (draft.isRest) {
            if (draft.totalDurationMinutes != 0) {
                v += "a REST workout must have totalDurationMinutes 0 but was ${draft.totalDurationMinutes}"
            }
            if (draft.segments.isNotEmpty()) {
                v += "a REST workout must have no segments but has ${draft.segments.size}"
            }
        } else {
            if (draft.segments.isEmpty()) {
                v += "workout has no segments"
            }
            if (draft.totalDurationMinutes <= 0) {
                v += "totalDurationMinutes must be > 0 but was ${draft.totalDurationMinutes}"
            }
        }
        if (draft.totalDurationMinutes > limits.maxTotalDurationMinutes) {
            v += "totalDurationMinutes ${draft.totalDurationMinutes} exceeds the safety ceiling " +
                "of ${limits.maxTotalDurationMinutes}"
        }
        if (draft.segments.isNotEmpty() && draft.totalDurationMinutes < limits.minTotalDurationMinutes) {
            v += "totalDurationMinutes ${draft.totalDurationMinutes} is below the minimum " +
                "of ${limits.minTotalDurationMinutes}"
        }

        val requireCompleteness = context is TrainingContextV2 &&
            (athlete.lactateThresholdHeartRateBpm != null || athlete.lactateThresholdPaceSecondsPerKm != null)
        draft.segments.forEachIndexed { i, s -> validateSegment(i, s, athlete, requireCompleteness, v) }

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
        requireCompleteness: Boolean,
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
        validatePercentLthrRange(at, s.heartRatePercentLthrMin, s.heartRatePercentLthrMax, v)
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

        // 6H-7.1: shape rules for the new target fields. These apply unconditionally - a legacy
        // draft never populates primaryTargetType/%LTHR/recovery, so none of this can ever fire for
        // one. Never a conversion, only accept-or-reject.
        val hasBpm = s.heartRateBpmMin != null || s.heartRateBpmMax != null
        val hasPercentLthr = s.heartRatePercentLthrMin != null || s.heartRatePercentLthrMax != null
        if (hasBpm && hasPercentLthr) {
            v += "$at has both an absolute heart-rate target and a %LTHR target; exactly one heart-rate " +
                "representation is allowed"
        }
        val pacePresent = s.paceSecondsPerKmFast != null && s.paceSecondsPerKmSlow != null
        val percentLthrPresent = s.heartRatePercentLthrMin != null && s.heartRatePercentLthrMax != null
        validatePrimaryTargetType(at, s.primaryTargetType, pacePresent, percentLthrPresent, v)

        if (s.recovery != null && s.recoveryDurationMinutes != null) {
            v += "$at has both a recovery object and the legacy recoveryDurationMinutes; exactly one " +
                "recovery representation is allowed"
        }
        if (s.recovery != null && s.repetitions == null) {
            v += "$at has a recovery object but no repetitions; recovery only applies to a repeated block"
        }
        s.recovery?.let { validateRecovery(at, it, athlete, v) }

        if (requireCompleteness && s.type != SegmentType.REST) {
            validateTargetCompleteness(at, s, athlete, v)
        }
    }

    private fun validateRecovery(at: String, r: WorkoutDraftRecovery, athlete: AthleteThresholds, v: MutableList<String>) {
        val rAt = "$at.recovery"
        if (r.durationMinutes <= 0) {
            v += "$rAt duration must be > 0 but was ${r.durationMinutes}"
        }
        validatePercentLthrRange(rAt, r.heartRatePercentLthrMin, r.heartRatePercentLthrMax, v)
        validateDoubleRange(rAt, "treadmill speed (kph)", r.treadmillSpeedKphMin, r.treadmillSpeedKphMax, v)
        listOfNotNull(r.inclinePercentMin, r.inclinePercentMax).forEach {
            if (!it.isFinite()) v += "$rAt incline is not a finite number"
            else if (abs(it) > MAX_ABS_INCLINE_PERCENT) v += "$rAt incline $it% is out of range"
        }
        if (r.paceSecondsPerKmFast != null && r.paceSecondsPerKmSlow != null &&
            r.paceSecondsPerKmFast > r.paceSecondsPerKmSlow
        ) {
            v += "$rAt pace range is inverted (fast ${r.paceSecondsPerKmFast}s/km is slower than " +
                "slow ${r.paceSecondsPerKmSlow}s/km)"
        }
        athlete.lactateThresholdPaceSecondsPerKm?.let { threshold ->
            val tolerance = properties.validation.pacePlausibilityTolerance
            listOfNotNull(r.paceSecondsPerKmFast, r.paceSecondsPerKmSlow).forEach { pace ->
                if (pace < threshold * (1 - tolerance) || pace > threshold / (1 - tolerance)) {
                    v += "$rAt pace ${pace}s/km is implausible against the athlete's threshold pace of ${threshold}s/km"
                }
            }
        }
        val pacePresent = r.paceSecondsPerKmFast != null && r.paceSecondsPerKmSlow != null
        val percentLthrPresent = r.heartRatePercentLthrMin != null && r.heartRatePercentLthrMax != null
        validatePrimaryTargetType(rAt, r.primaryTargetType, pacePresent, percentLthrPresent, v)
    }

    /**
     * `null` (legacy drafts) is always accepted - the pre-6H-7.1 inference rule applies downstream
     * (pace present -> PACE, else qualitative/none). When explicitly set, the chosen type's target
     * must be fully present and the other physiological target must be absent: a draft never claims
     * PACE while also carrying a complete %LTHR pair (or vice versa) as if both applied.
     */
    private fun validatePrimaryTargetType(
        at: String,
        type: PrimaryTargetType?,
        pacePresent: Boolean,
        percentLthrPresent: Boolean,
        v: MutableList<String>,
    ) {
        if (type == null) return
        when (type) {
            PrimaryTargetType.PACE -> {
                if (!pacePresent) v += "$at declares primaryTargetType PACE but has no complete pace target"
                if (percentLthrPresent) v += "$at declares primaryTargetType PACE but also carries a %LTHR target"
            }
            PrimaryTargetType.HEART_RATE -> {
                if (!percentLthrPresent) v += "$at declares primaryTargetType HEART_RATE but has no complete %LTHR target"
                if (pacePresent) v += "$at declares primaryTargetType HEART_RATE but also carries a pace target"
            }
            PrimaryTargetType.QUALITATIVE, PrimaryTargetType.NONE -> {
                if (pacePresent) v += "$at declares primaryTargetType $type but also carries a pace target"
                if (percentLthrPresent) v += "$at declares primaryTargetType $type but also carries a %LTHR target"
            }
        }
    }

    /**
     * 6H-7.1, V2 only: when the athlete has a threshold V2 can see, a real running segment must
     * carry the target that threshold actually supports (pace-only athlete -> PACE; LTHR-only ->
     * HEART_RATE; both -> either, the coach's call). No threshold at all is handled by the caller
     * (the whole rule is skipped), so this is never reached when there is nothing to use.
     */
    private fun validateTargetCompleteness(at: String, s: WorkoutDraftSegment, athlete: AthleteThresholds, v: MutableList<String>) {
        val pacePresent = s.paceSecondsPerKmFast != null && s.paceSecondsPerKmSlow != null
        val percentLthrPresent = s.heartRatePercentLthrMin != null && s.heartRatePercentLthrMax != null
        val hasPace = athlete.lactateThresholdPaceSecondsPerKm != null
        val hasLthr = athlete.lactateThresholdHeartRateBpm != null
        val satisfied = (hasPace && pacePresent) || (hasLthr && percentLthrPresent)
        if (!satisfied) {
            val available = listOfNotNull(
                "pace".takeIf { hasPace },
                "%LTHR".takeIf { hasLthr },
            ).joinToString(" or ")
            v += "$at has no device target even though the athlete's $available threshold is known " +
                "(V2 target completeness)"
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

    private fun validatePercentLthrRange(at: String, lower: Int?, upper: Int?, v: MutableList<String>) {
        if ((lower == null) != (upper == null)) {
            v += "$at has only one bound of its %LTHR range; a half range is never accepted"
        }
        listOfNotNull(lower, upper).forEach {
            if (it <= 0) v += "$at %LTHR must be > 0 but was $it"
        }
        if (lower != null && upper != null && lower > upper) {
            v += "$at %LTHR range is inverted ($lower > $upper)"
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
