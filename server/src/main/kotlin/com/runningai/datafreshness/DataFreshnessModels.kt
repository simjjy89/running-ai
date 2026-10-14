package com.runningai.datafreshness

import com.fasterxml.jackson.annotation.JsonInclude
import com.runningai.integration.garmin.GarminRecoveryCoverageResponse
import java.time.Instant
import java.time.LocalDate

/**
 * The collection pipelines this report covers. Each is a distinct way data reaches RunningAI, so each
 * can be stale, failing or simply idle independently of the others.
 */
enum class DataSource {
    /** Garmin activity summaries via the incremental sync (`garmin_sync_state` / `garmin_sync_attempt`). */
    GARMIN_ACTIVITY,

    /** Garmin daily recovery metrics (`garmin_recovery_daily` / `garmin_recovery_collection`). */
    GARMIN_RECOVERY,

    /** Intervals.icu fitness model: calculated fitness (CTL) / fatigue (ATL) days. */
    INTERVALS_FITNESS,

    /** Activities whose only home is Intervals.icu (Phase 7E-1 import). */
    INTERVALS_ACTIVITY,

    /** Activities recovered from the athlete's Strava export (Phase 7E-2 import). */
    STRAVA_ACTIVITY,
}

/**
 * How current one source is, and whether that can be trusted.
 *
 * The distinction the whole report exists for is [collectionTracked]. Where it is true, the database
 * records the outcome of every attempt, so an absence of recent data can be attributed: Garmin had
 * nothing, nothing ran, or collection failed. Where it is false the source keeps no attempt ledger -
 * a one-off manual import, for instance - and this report says so rather than implying that "no
 * recent data" means "nothing new exists".
 *
 * @param latestDataDate newest data the source has produced, searched without a window so a badly
 *   stale source reports its real age instead of looking like it has no data at all
 * @param storedRecordCount records inside the reported window only (see [DataFreshnessResponse.windowDays])
 * @param lastSuccessfulCollectionAt when collection last SUCCEEDED, which is not the same as when it
 *   was last tried ([lastAttemptAt])
 * @param lastFailureAt kept even after a later success, so "when did this last break?" stays answerable
 * @param consecutiveFailureCount back to zero as soon as an attempt completes; a growing number is
 *   the signal that a scheduler is running and failing every time
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class DataSourceFreshness(
    val source: DataSource,
    val latestDataDate: LocalDate?,
    val dataAgeDays: Int?,
    val storedRecordCount: Long,
    val collectionTracked: Boolean,
    val lastAttemptAt: Instant?,
    val lastAttemptOutcome: String?,
    val lastSuccessfulCollectionAt: Instant?,
    val collectionAgeHours: Long?,
    val lastFailureAt: Instant?,
    val lastFailureCode: String?,
    val consecutiveFailureCount: Int?,
)

/**
 * Whether the stored running analyses are current, counted over the window's activities.
 *
 * "Outdated" is [com.runningai.analysis.AnalysisReusePolicy]'s answer, not a local version comparison:
 * a stored analysis is current only under the current output contract AND the current engine
 * revision. Counts describe the DATA, never the sessions.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class AnalysisFreshness(
    val runningActivityCount: Int,
    val analysisCurrent: Int,
    val analysisMissingOrOutdated: Int,
)

/**
 * `GET /api/v1/data-freshness`: one database-only view of how current every collection pipeline is.
 *
 * Makes no Garmin, Intervals or Claude call, so it can be polled freely and never itself changes what
 * it reports. Facts only - it states ages, counts and recorded outcomes, and deliberately draws no
 * conclusion about whether the data is "good enough": that judgement belongs to
 * [com.runningai.coachrefresh.CoachDataFreshnessEvaluator] for a coach decision, and to the operator
 * otherwise.
 *
 * @param windowDays the window [storedRecordCount][DataSourceFreshness.storedRecordCount], [recovery]
 *   and [analysis] are counted over, ending on [date]
 * @param recovery per-day recovery coverage, which is where "how many days are missing, and is that
 *   because Garmin has nothing or because collection never ran?" is answered
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class DataFreshnessResponse(
    val date: LocalDate,
    val windowStartDate: LocalDate,
    val windowDays: Int,
    val generatedAt: Instant,
    val sources: List<DataSourceFreshness>,
    val recovery: GarminRecoveryCoverageResponse,
    val analysis: AnalysisFreshness,
)
