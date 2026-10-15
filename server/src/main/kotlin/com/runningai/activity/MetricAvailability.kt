package com.runningai.activity

/**
 * A family of metrics the coach might look for and will not find (Phase 8B, Workstream F).
 *
 * These are structural absences, not measurements. They exist because RunningAI's history comes
 * from three sources with genuinely different content: Garmin Connect holds nothing before
 * 2026-09-18, the sessions before that were recovered from Intervals.icu and a Strava archive
 * export, and each of those two carries less than the device originally recorded.
 */
enum class UnavailableMetric {
    /** Cadence (spm). */
    CADENCE,

    /** Ground contact time, vertical oscillation, vertical ratio, stride length. */
    RUNNING_DYNAMICS,

    /** Per-lap splits. */
    LAP_STRUCTURE,

    /** Interval groups: which laps formed a repeated work/recovery block. */
    INTERVAL_GROUPS,

    /** Time-in-heart-rate-zone. */
    HEART_RATE_ZONES,
}

/**
 * One metric family that is unavailable for some of the activities in the context, with the reason
 * and how many are affected.
 *
 * The reason is a fixed statement of fact about the source, never an assessment. "Cadence is not
 * available" and "cadence was poor" are opposite claims, and before this existed the context said
 * neither - it simply omitted the field, which reads to a coach as the latter.
 */
data class MetricUnavailabilityNote(
    val metric: UnavailableMetric,
    val reason: String,
    val affectedActivityCount: Int,
)

/**
 * Which metrics a stored activity cannot have, decided by where it came from (Phase 8B).
 *
 * Lives beside [ExternalSource] rather than in either consumer: it is a fact about where an
 * activity came from, and both the coach context and the training-analysis report need to say it.
 *
 * Pure and table-driven, keyed on `external_source` alone - no inspection of the rows themselves.
 * That is deliberate: these absences are properties of the IMPORT, decided once and documented in
 * `docs/architecture/intervals-activity-import.md` and `strava-archive-import.md`, so reading them
 * back out of the data would just be re-deriving a known fact and could silently disagree with it.
 *
 * Why each one is absent, in short:
 *  - Intervals.icu and Strava both report cadence as one-leg rpm while RunningAI's column is
 *    Garmin's spm (factor 2). Neither importer converts it - a doubled number that looks right is
 *    worse than an honest gap - so the column stays null and the raw stream keeps its source key.
 *  - Running dynamics have no column at all for those sources, and the analysis engine reads
 *    dynamics from laps, which an Intervals import has none of.
 *  - An Intervals activity's splits come back empty, and no lap is ever invented.
 *  - Strava's re-encoded FIT restores laps but carries no `intensity` and no `wkt_step_index`, so
 *    which laps formed a repeat block cannot be known; inferring it from pace is banned.
 *  - Intervals uses a 7-zone model against Garmin's 5, so its zones are not imported.
 */
object MetricAvailability {

    private val INTERVALS_ONLY_ABSENCES = listOf(
        UnavailableMetric.CADENCE,
        UnavailableMetric.RUNNING_DYNAMICS,
        UnavailableMetric.LAP_STRUCTURE,
        UnavailableMetric.INTERVAL_GROUPS,
        UnavailableMetric.HEART_RATE_ZONES,
    )

    private val STRAVA_ABSENCES = listOf(
        UnavailableMetric.CADENCE,
        UnavailableMetric.RUNNING_DYNAMICS,
        UnavailableMetric.INTERVAL_GROUPS,
    )

    private val REASONS = mapOf(
        UnavailableMetric.CADENCE to
            "Imported from Intervals.icu or a Strava archive, which report one-leg rpm; RunningAI's " +
            "column is Garmin's spm and the importers deliberately do not convert it. No cadence was " +
            "recorded for this session at all; this is an absence of measurement, not a value.",
        UnavailableMetric.RUNNING_DYNAMICS to
            "Ground contact time, vertical oscillation and stride length are only collected from Garmin. " +
            "Absent for imported activities, which says nothing about the running form.",
        UnavailableMetric.LAP_STRUCTURE to
            "Intervals.icu returns no splits for these activities and no lap is ever invented, so the " +
            "session has no per-lap breakdown - not a session run without laps.",
        UnavailableMetric.INTERVAL_GROUPS to
            "Interval groups are read from lap intensity and workout-step index, which imported laps do " +
            "not carry. An interval session can therefore show no groups; it was not necessarily continuous.",
        UnavailableMetric.HEART_RATE_ZONES to
            "Intervals.icu uses a 7-zone model against Garmin's 5, so its zones are not imported rather " +
            "than being mapped onto a different scale.",
    )

    /** What is structurally unavailable for an activity from [source]. */
    fun unavailableFor(source: ExternalSource?): List<UnavailableMetric> = when (source) {
        ExternalSource.INTERVALS_ICU -> INTERVALS_ONLY_ABSENCES
        ExternalSource.STRAVA -> STRAVA_ABSENCES
        else -> emptyList()
    }

    /**
     * The notes for a set of activities: one entry per affected metric, with the reason stated
     * once rather than repeated on every activity, and the number of activities it applies to.
     * Empty when every activity came from Garmin.
     */
    fun notesFor(sources: List<ExternalSource?>): List<MetricUnavailabilityNote> {
        val counts = sources
            .flatMap { unavailableFor(it) }
            .groupingBy { it }
            .eachCount()
        return UnavailableMetric.entries
            .filter { counts.containsKey(it) }
            .map { MetricUnavailabilityNote(it, requireNotNull(REASONS[it]), counts.getValue(it)) }
    }
}
