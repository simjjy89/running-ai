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
 * Mostly table-driven, keyed on `external_source`: these absences are properties of the IMPORT,
 * decided once and documented in `docs/architecture/intervals-activity-import.md` and
 * `strava-archive-import.md`, so reading them back out of the data would just be re-deriving a
 * known fact.
 *
 * [UnavailableMetric.LAP_STRUCTURE] is the one exception, and not by taste. It is not a property
 * of the source at all: an activity imported from Intervals.icu arrives with no splits, but the
 * Strava archive import (Phase 7E-2) later writes laps into exactly those activities where it has
 * them, so the same row can go from having no lap breakdown to having one without its
 * `external_source` ever changing. Keying it on the source therefore told the coach that sessions
 * it CAN see per-lap data for have none - measured on the real history, all 25 Intervals-sourced
 * activities carry laps, 67 of them written by the Strava importer. Whether laps exist is asked of
 * the data, because only the data knows.
 *
 * Why each one is absent, in short:
 *  - Intervals.icu and Strava both report cadence as one-leg rpm while RunningAI's column is
 *    Garmin's spm (factor 2). Neither importer converts it - a doubled number that looks right is
 *    worse than an honest gap - so the column stays null and the raw stream keeps its source key.
 *  - Running dynamics have no column at all for those sources, and the analysis engine reads
 *    dynamics from laps, which an Intervals import has none of.
 *  - An Intervals activity's splits come back empty and no lap is ever invented, so it has no
 *    laps unless the Strava archive import later supplied them.
 *  - Strava's re-encoded FIT restores laps but carries no `intensity` and no `wkt_step_index`, so
 *    which laps formed a repeat block cannot be known; inferring it from pace is banned. That is
 *    true of a Strava-written lap wherever it ends up, which is why an Intervals-sourced activity
 *    enriched from the archive gains its laps and still has no interval groups.
 *  - Neither importer writes `activity_zone`: Intervals uses a 7-zone model against Garmin's 5 and
 *    is not mapped onto a different scale, and a Strava archive carries no zone table at all.
 */
object MetricAvailability {

    /**
     * What the SOURCE decides, for every metric except [UnavailableMetric.LAP_STRUCTURE] - which no
     * source decides, because a later import can add laps to an already-stored activity.
     */
    private val INTERVALS_ONLY_ABSENCES = listOf(
        UnavailableMetric.CADENCE,
        UnavailableMetric.RUNNING_DYNAMICS,
        UnavailableMetric.INTERVAL_GROUPS,
        UnavailableMetric.HEART_RATE_ZONES,
    )

    private val STRAVA_ABSENCES = listOf(
        UnavailableMetric.CADENCE,
        UnavailableMetric.RUNNING_DYNAMICS,
        UnavailableMetric.INTERVAL_GROUPS,
        UnavailableMetric.HEART_RATE_ZONES,
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
            "No laps are stored for this session, so there is no per-lap breakdown to read. Garmin laps " +
            "arrive with the per-activity detail collection and are absent until it has run; Intervals.icu " +
            "returns no splits and no lap is ever invented, so an activity recovered that way has laps only " +
            "where the Strava archive import supplied them. The breakdown is absent; this is not a session " +
            "that was run without laps.",
        UnavailableMetric.INTERVAL_GROUPS to
            "Interval groups are read from lap intensity and workout-step index, which imported laps do " +
            "not carry - including laps an Intervals-sourced activity received from the Strava archive. " +
            "An interval session can therefore show no groups; it was not necessarily continuous.",
        UnavailableMetric.HEART_RATE_ZONES to
            "Neither import writes time-in-zone: Intervals.icu uses a 7-zone model against Garmin's 5 and " +
            "is not mapped onto a different scale, and a Strava archive carries no zone table at all.",
    )

    /**
     * One stored activity, as far as this object needs to know it.
     *
     * @param externalSource where the activity came from
     * @param hasLapStructure whether any lap row is actually stored for it. Asked of the data
     *        rather than inferred from [externalSource], because the Strava archive import writes
     *        laps into activities imported from Intervals.icu - see the note on this object.
     */
    data class ActivityMetricSource(val externalSource: ExternalSource?, val hasLapStructure: Boolean)

    /**
     * What is structurally unavailable for an activity from [source] that does, or does not
     * ([hasLapStructure]), have stored laps. Ordered by [UnavailableMetric] so the answer is stable.
     */
    fun unavailableFor(source: ExternalSource?, hasLapStructure: Boolean): List<UnavailableMetric> {
        val fromSource = when (source) {
            ExternalSource.INTERVALS_ICU -> INTERVALS_ONLY_ABSENCES
            ExternalSource.STRAVA -> STRAVA_ABSENCES
            else -> emptyList()
        }.toSet()
        return UnavailableMetric.entries.filter {
            if (it == UnavailableMetric.LAP_STRUCTURE) !hasLapStructure else it in fromSource
        }
    }

    /**
     * The notes for a set of activities: one entry per affected metric, with the reason stated
     * once rather than repeated on every activity, and the number of activities it applies to.
     * Empty when nothing is missing from any of them.
     */
    fun notesFor(activities: List<ActivityMetricSource>): List<MetricUnavailabilityNote> {
        val counts = activities
            .flatMap { unavailableFor(it.externalSource, it.hasLapStructure) }
            .groupingBy { it }
            .eachCount()
        return UnavailableMetric.entries
            .filter { counts.containsKey(it) }
            .map { MetricUnavailabilityNote(it, requireNotNull(REASONS[it]), counts.getValue(it)) }
    }
}
