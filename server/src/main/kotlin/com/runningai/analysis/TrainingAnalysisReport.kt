package com.runningai.analysis

import com.fasterxml.jackson.annotation.JsonInclude
import com.runningai.activity.ActivityType
import com.runningai.activity.ExternalSource
import com.runningai.activity.MetricUnavailabilityNote
import java.time.LocalDate

/**
 * A descriptive report over everything RunningAI has stored (Phase 8B, Workstream F).
 *
 * It obeys this package's one rule without exception: **it measures, it never judges.** No rating,
 * score, threshold, readiness or risk number, and no good/bad label appears anywhere in it. A
 * trend is reported as the numbers at each end and the difference between them; whether that
 * difference is progress, a plateau or a problem is the AI coach's call, and in several cases it
 * is genuinely unknowable from this data alone - a slower month of easy running and a slower month
 * of declining fitness look identical here, and nothing in this report pretends otherwise.
 *
 * It is also strictly database-only: no Garmin call, no Intervals call, no coach call, and it
 * never triggers an analysis. An activity with no stored analysis is counted as unanalysed, not
 * silently analysed on read.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class TrainingAnalysisReport(
    val generatedFor: LocalDate,
    val coverage: ReportCoverage,
    val volume: List<VolumePeriod>,
    val bestEfforts: List<BestEffort>,
    val durability: DurabilityTrend,
    val runningDynamics: DynamicsTrend,
    /**
     * Everything that could not be measured, and why. Reported first-class rather than left as
     * absent fields, because an absence read as a value is the single easiest way to misread a
     * report like this one.
     */
    val limitations: ReportLimitations,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class ReportCoverage(
    val oldestActivityDate: LocalDate?,
    val newestActivityDate: LocalDate?,
    /** Calendar span of the stored history; not a count of days trained. */
    val historySpanDays: Int?,
    val activityCount: Int,
    val runningActivityCount: Int,
    val analysedActivityCount: Int,
    /** How many activities came from each source, since the sources carry different metrics. */
    val activityCountBySource: Map<ExternalSource, Int>,
)

/**
 * One equal-length slice of the history, newest last. Equal-length so two slices are comparable;
 * a partial slice at either end is flagged rather than scaled up to look full.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class VolumePeriod(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val activityCount: Int,
    val totalDistanceKm: Double?,
    val totalDurationMinutes: Long,
    val longestRunKm: Double?,
)

/**
 * The fastest stored activity whose distance is close to a standard race distance.
 *
 * **Not a personal best and not a race result.** It is the quickest WHOLE activity of roughly that
 * length that RunningAI happens to hold - which may be a training run, may include stops, and is
 * only as complete as the import it came from. A real best effort would need a rolling window over
 * the sample stream, which this report deliberately does not compute: the imported half of the
 * history has no sample stream fit for it, so the number would mean different things for different
 * activities.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class BestEffort(
    val label: String,
    val targetDistanceKm: Double,
    val tolerancePercent: Double,
    val matchingActivityCount: Int,
    val fastestDate: LocalDate?,
    val fastestDistanceKm: Double?,
    val fastestDurationSeconds: Int?,
    val fastestPaceSecondsPerKm: Int?,
    /** The most recent matching activity, so a fast old run is never mistaken for current form. */
    val mostRecentDate: LocalDate?,
    val mostRecentPaceSecondsPerKm: Int?,
)

/**
 * How sessions held together from first half to second, across the analysed runs.
 *
 * Both metrics come straight from `activity_analysis` and keep their existing definitions: the
 * halves split at the **elapsed-time midpoint**, and `speedHrDecouplingPercent` is
 * `(EF1-EF2)/EF1*100` where `EF = avgSpeed/avgHR` - a RunningAI-derived descriptive metric with no
 * threshold attached, not a Garmin or Intervals.icu score. No threshold is attached here either.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class DurabilityTrend(
    val speedChangePercent: MetricTrend,
    val hrChangePercent: MetricTrend,
    val speedHrDecouplingPercent: MetricTrend,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class DynamicsTrend(
    val cadenceSpm: MetricTrend,
    val strideLengthCm: MetricTrend,
    val groundContactTimeMs: MetricTrend,
    val verticalOscillationCm: MetricTrend,
)

/**
 * One measured quantity over the history: the mean of its earliest half and of its latest half,
 * and the difference. Nothing is said about what the difference means.
 *
 * [sampleCount] is the number of activities that actually carried the metric, which is often far
 * fewer than the number of activities - only Garmin-sourced runs carry dynamics at all. A trend
 * computed from two or three sessions is reported with its count rather than suppressed, so the
 * reader can see how thin it is; [comparable] is false when either half has nothing in it.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class MetricTrend(
    val sampleCount: Int,
    val earlierMean: Double?,
    val laterMean: Double?,
    val change: Double?,
    val overallMean: Double?,
    val comparable: Boolean,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class ReportLimitations(
    /** Activities with no stored analysis: their metrics are absent from every trend above. */
    val unanalysedActivityCount: Int,
    /** Analyses whose status was not COMPLETE, so some of their metrics are null by design. */
    val partialAnalysisCount: Int,
    /** Metric families some activities structurally cannot have, with the reason. */
    val metricUnavailability: List<MetricUnavailabilityNote>,
    /**
     * The sentence this report exists to make unmissable. Stated as data rather than left to the
     * reader to remember.
     */
    val notes: List<String>,
)

/** Activity types this report measures. A cross-training session is not a run and is not averaged in. */
internal val REPORTED_RUN_TYPES = setOf(ActivityType.RUN, ActivityType.TREADMILL_RUN)
