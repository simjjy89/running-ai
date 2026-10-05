package com.runningai.coachrefresh

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant
import java.time.LocalDate

/** A coach data refresh is already running in this JVM; the request is rejected, never queued. */
class CoachDataRefreshAlreadyRunningException :
    RuntimeException("A coach data refresh is already running")

/** Outcome of the Garmin incremental activity-summary sync stage. */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class GarminRefreshSummary(
    val success: Boolean,
    val lastSuccessfulSyncAt: Instant?,
    val syncAgeMinutes: Long?,
    val fetched: Int,
    val created: Int,
    val updated: Int,
    val failed: Int,
    val checkpointAdvanced: Boolean,
    /** Null when [success]; otherwise a short reason code, never a raw exception message. */
    val failureCode: String?,
)

/** Outcome of the recent-activity-completion stage (detail collection, running analysis, Intervals). */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class RecentActivityRefreshSummary(
    val recentActivityCount: Int,
    val recentRunningActivityCount: Int,
    val detailComplete: Int,
    val detailCollected: Int,
    val detailFailed: Int,
    val analysisCurrent: Int,
    val analysisComputed: Int,
    val analysisMissing: Int,
    val fullSamples: Int,
    val noSampleStreams: Int,
    val sampleIncomplete: Int,
    val intervalsMatched: Int,
    val intervalsUnmatched: Int,
    val intervalsAmbiguous: Int,
    /** Null unless an Intervals activity-enrichment call hit an account-level failure (stops the loop). */
    val intervalsFailureCode: String?,
)

/** Outcome of the Intervals.icu fitness-model refresh stage (D-7..D). */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class IntervalsFitnessRefreshSummary(
    val oldest: LocalDate?,
    val newest: LocalDate?,
    val daysFetched: Int?,
    val daysStored: Int?,
    val failed: Boolean,
    val failureCode: String?,
)

/** Outcome of the Garmin recovery refresh stage (D-1..D, at most 2 days). */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class RecoveryRefreshSummary(
    val daysAttempted: Int,
    val daysUpdated: Int,
    val completed: Boolean,
    val failed: Boolean,
    val failureCode: String?,
)

/** DB-only freshness facts (Phase 6H-9 section 27): no network call is made to compute this. */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class CoachDataFreshness(
    val newestActivityDate: LocalDate?,
    val latestFitnessDate: LocalDate?,
    val fitnessAgeDays: Int?,
    val latestRecoveryDate: LocalDate?,
    val recoveryAgeDays: Int?,
)

/** Full report of one coach-data-refresh run (`POST /api/v1/coach/data-refresh`). */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class CoachDataRefreshResult(
    val date: LocalDate,
    val readyForCoach: Boolean,
    val reasons: List<String>,
    val garmin: GarminRefreshSummary,
    val activities: RecentActivityRefreshSummary,
    val intervalsFitness: IntervalsFitnessRefreshSummary,
    val recovery: RecoveryRefreshSummary,
    val freshness: CoachDataFreshness,
)
