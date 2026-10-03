package com.runningai.enrichment

import java.time.Instant
import java.time.LocalDate

/**
 * Phase 6H-5 models. Terminology is fixed here once:
 * CTL = Intervals.icu calculated fitness, ATL = Intervals.icu calculated fatigue. The wellness field
 * `fatigue` is a subjective self-report and never reaches these models — there is deliberately no
 * field it could be mapped to.
 */

/** Raw payload kinds stored in `intervals_raw_payload`. Only implemented capabilities are listed. */
enum class IntervalsPayloadType { ACTIVITY, WELLNESS_DAY }

/** How an external activity was linked to a RunningAI activity. */
enum class MatchMethod { SOURCE_ID, EXTERNAL_ID, COMPOSITE }

/**
 * One Intervals.icu completed-activity item, reduced to the fields enrichment needs. Field names here
 * are RunningAI's; the Intervals.icu names (`icu_ctl`, ...) end at the mapper. Values are never
 * defaulted: a missing source field stays null.
 */
data class IntervalsActivitySnapshot(
    /** Intervals.icu activity id, e.g. `i1882...`. */
    val intervalsActivityId: String,
    /** The payload's `source` label, e.g. `GARMIN_CONNECT`; null when absent. */
    val source: String?,
    /** The payload's `external_id` — for GARMIN_CONNECT activities this is the Garmin activity id. */
    val externalId: String?,
    /** Intervals.icu activity type label, e.g. `Run`, `VirtualRun`, `VirtualRide`. */
    val type: String?,
    val startDate: Instant?,
    val elapsedTimeSeconds: Int?,
    val movingTimeSeconds: Int?,
    val distanceMeters: Double?,
    /** `icu_training_load`. */
    val trainingLoad: Int?,
    /** `icu_intensity`. */
    val intensity: Double?,
    /** `icu_ctl`: Intervals calculated fitness after this activity. */
    val ctlAfterActivity: Double?,
    /** `icu_atl`: Intervals calculated fatigue after this activity. */
    val atlAfterActivity: Double?,
    /** `analyzed`: when Intervals.icu last computed these numbers. */
    val sourceUpdatedAt: Instant?,
)

/** One Intervals.icu wellness entry's fitness-model numbers. Subjective fields are not represented. */
data class IntervalsWellnessDay(
    /** The entry's own id, an ISO date. */
    val date: LocalDate,
    /** `ctl`: calculated fitness. */
    val ctl: Double?,
    /** `atl`: calculated fatigue. Never the subjective `fatigue` field. */
    val atl: Double?,
    val ctlLoad: Double?,
    val atlLoad: Double?,
    val rampRate: Double?,
    /** `updated`. */
    val sourceUpdatedAt: Instant?,
) {
    /** RunningAI derived from Intervals CTL/ATL; null unless both are present. Not a source field. */
    val derivedForm: Double? = if (ctl != null && atl != null) ctl - atl else null
}

/** Technical matching evidence (for debugging), not a training metric. */
data class MatchEvidence(
    val startTimeDeltaSeconds: Int?,
    val durationDeltaSeconds: Int?,
    val distanceDeltaMeters: Double?,
)

/** Outcome of matching one RunningAI activity against the Intervals candidates of its date window. */
sealed interface MatchResult {
    data class Matched(
        val snapshot: IntervalsActivitySnapshot,
        val method: MatchMethod,
        val evidence: MatchEvidence,
    ) : MatchResult

    data object Unmatched : MatchResult

    /** 2+ composite candidates: linking automatically is forbidden, the caller records the count. */
    data class Ambiguous(val candidateCount: Int) : MatchResult
}

/** Stored form of one activity's Intervals metrics, as read back from `activity_intervals_metrics`. */
data class ActivityIntervalsMetricsData(
    val activityId: Long,
    val intervalsActivityId: String,
    val trainingLoad: Int?,
    val intensity: Double?,
    val ctlAfterActivity: Double?,
    val atlAfterActivity: Double?,
    val sourceUpdatedAt: Instant?,
    val fetchedAt: Instant,
)

/** Stored form of one day in `intervals_fitness_daily`. */
data class IntervalsFitnessDayData(
    val date: LocalDate,
    val ctl: Double?,
    val atl: Double?,
    val derivedForm: Double?,
    val rampRate: Double?,
    val ctlLoad: Double?,
    val atlLoad: Double?,
    val sourceUpdatedAt: Instant?,
    val fetchedAt: Instant,
)

/** Stored form of one row in `activity_source_link`. */
data class ActivitySourceLinkData(
    val activityId: Long,
    val externalSource: com.runningai.activity.ExternalSource,
    val externalActivityId: String,
    val matchMethod: MatchMethod,
    val evidence: MatchEvidence,
    val matchedAt: Instant,
)
