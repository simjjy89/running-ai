package com.runningai.datafreshness

import com.runningai.activity.Activity
import com.runningai.activity.ActivityRepository
import com.runningai.activity.ActivityType
import com.runningai.activity.ExternalSource
import com.runningai.analysis.ActivityAnalysisStore
import com.runningai.analysis.AnalysisReusePolicy
import com.runningai.athlete.AthleteService
import com.runningai.enrichment.IntervalsEnrichmentStore
import com.runningai.integration.garmin.GarminRecoveryCollectionService
import com.runningai.integration.garmin.GarminSyncAttempt
import com.runningai.integration.garmin.GarminSyncAttemptService
import com.runningai.integration.garmin.GarminSyncStateService
import com.runningai.recovery.RecoveryRepository
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Answers "how current is each source, and can I trust that answer?" from stored rows only (Phase 8A).
 *
 * Zero network calls: every number comes from a table RunningAI already keeps. That is what makes it
 * safe to call before deciding whether to run a collection at all - the existing way to learn this was
 * `POST /api/v1/coach/data-refresh`, which answers the question by actually fetching.
 *
 * It reuses rather than reimplements: the recovery picture is
 * [GarminRecoveryCollectionService.coverage], and "is this analysis current?" is
 * [AnalysisReusePolicy], the single authority the backfill and the coach freshness evaluator also use.
 */
@Service
class DataFreshnessService(
    private val athleteService: AthleteService,
    private val activities: ActivityRepository,
    private val syncStateService: GarminSyncStateService,
    private val syncAttemptService: GarminSyncAttemptService,
    private val recoveryCollectionService: GarminRecoveryCollectionService,
    private val recoveryRepository: RecoveryRepository,
    private val intervalsStore: IntervalsEnrichmentStore,
    private val analysisStore: ActivityAnalysisStore,
    private val clock: Clock,
) {

    fun freshness(requestedDate: LocalDate?, requestedWindowDays: Int?): DataFreshnessResponse {
        val athlete = athleteService.defaultAthlete
        val zone = ZoneId.of(athlete.timezone)
        val now = clock.instant()
        val date = requestedDate ?: LocalDate.now(clock.withZone(zone))
        val windowDays = (requestedWindowDays ?: DEFAULT_WINDOW_DAYS).coerceIn(1, MAX_WINDOW_DAYS)
        val windowStart = date.minusDays(windowDays - 1L)

        val windowActivities = activities.findByAthleteIdAndStartedAtGreaterThanEqualAndStartedAtLessThan(
            athlete.id,
            windowStart.atStartOfDay(zone).toInstant(),
            date.plusDays(1).atStartOfDay(zone).toInstant(),
        )

        return DataFreshnessResponse(
            date = date,
            windowStartDate = windowStart,
            windowDays = windowDays,
            generatedAt = now,
            sources = listOf(
                garminActivity(athlete.id, zone, date, now, windowActivities),
                garminRecovery(athlete.id, date, windowStart, now),
                intervalsFitness(athlete.id, date, windowStart),
                importedActivities(DataSource.INTERVALS_ACTIVITY, ExternalSource.INTERVALS_ICU, athlete.id, zone, date, windowActivities),
                importedActivities(DataSource.STRAVA_ACTIVITY, ExternalSource.STRAVA, athlete.id, zone, date, windowActivities),
            ),
            recovery = recoveryCollectionService.coverage(athlete.id, windowStart, date),
            analysis = analysis(windowActivities),
        )
    }

    /**
     * The only source with a full picture: a checkpoint for what succeeded and an attempt record for
     * what was last tried. Its latest data date is the newest GARMIN activity, never the newest
     * activity of any source - an Intervals import must not make the Garmin sync look current.
     */
    private fun garminActivity(
        athleteId: Long,
        zone: ZoneId,
        date: LocalDate,
        now: Instant,
        windowActivities: List<Activity>,
    ): DataSourceFreshness {
        val lastSuccess = syncStateService.find(athleteId).map { it.lastSuccessfulSyncAt }.orElse(null)
        val attempt: GarminSyncAttempt? = syncAttemptService.find(athleteId).orElse(null)
        val latestDataDate = activities
            .findFirstByAthleteIdAndExternalSourceOrderByStartedAtDesc(athleteId, ExternalSource.GARMIN)
            .map { it.startedAt.atZone(zone).toLocalDate() }
            .orElse(null)
        return DataSourceFreshness(
            source = DataSource.GARMIN_ACTIVITY,
            latestDataDate = latestDataDate,
            dataAgeDays = latestDataDate?.let { daysBetween(it, date) },
            storedRecordCount = windowActivities.count { it.externalSource == ExternalSource.GARMIN }.toLong(),
            collectionTracked = true,
            lastAttemptAt = attempt?.attemptedAt,
            lastAttemptOutcome = attempt?.outcome?.name,
            lastSuccessfulCollectionAt = lastSuccess,
            collectionAgeHours = lastSuccess?.let { ChronoUnit.HOURS.between(it, now) },
            lastFailureAt = attempt?.lastFailureAt,
            lastFailureCode = attempt?.lastFailureCode,
            consecutiveFailureCount = attempt?.consecutiveFailureCount,
        )
    }

    /**
     * Recovery is collected per day, so its "last successful collection" is the newest attempt that
     * did not fail - there is no single checkpoint row. The day-level detail lives in
     * [DataFreshnessResponse.recovery]; this entry is the summary beside the other sources.
     */
    private fun garminRecovery(
        athleteId: Long,
        date: LocalDate,
        windowStart: LocalDate,
        now: Instant,
    ): DataSourceFreshness {
        val wideStart = date.minusDays(WIDE_LOOKBACK_DAYS)
        val storedDays = recoveryRepository
            .findByAthleteIdAndRecoveryDateBetweenOrderByRecoveryDateAsc(athleteId, wideStart, date)
        val latestDataDate = storedDays.maxByOrNull { it.recoveryDate }?.recoveryDate
        val attempts = recoveryCollectionService.coverage(athleteId, wideStart, date).days()
            .filter { it.lastAttemptedAt() != null }
        val lastAttempt = attempts.maxByOrNull { it.lastAttemptedAt() }
        val lastSuccess = attempts.filter { it.lastFailureCode() == null }.maxByOrNull { it.lastAttemptedAt() }
        val lastFailure = attempts.filter { it.lastFailureCode() != null }.maxByOrNull { it.lastAttemptedAt() }
        return DataSourceFreshness(
            source = DataSource.GARMIN_RECOVERY,
            latestDataDate = latestDataDate,
            dataAgeDays = latestDataDate?.let { daysBetween(it, date) },
            storedRecordCount = storedDays.count { !it.recoveryDate.isBefore(windowStart) }.toLong(),
            collectionTracked = true,
            lastAttemptAt = lastAttempt?.lastAttemptedAt(),
            lastAttemptOutcome = lastAttempt?.lastCollectionOutcome()?.name,
            lastSuccessfulCollectionAt = lastSuccess?.lastAttemptedAt(),
            collectionAgeHours = lastSuccess?.lastAttemptedAt()?.let { ChronoUnit.HOURS.between(it, now) },
            lastFailureAt = lastFailure?.lastAttemptedAt(),
            lastFailureCode = lastFailure?.lastFailureCode(),
            // One row per day rather than one streak per source: a per-day ledger has no single
            // streak to report, and deriving one from the day order would be a guess.
            consecutiveFailureCount = null,
        )
    }

    private fun intervalsFitness(athleteId: Long, date: LocalDate, windowStart: LocalDate): DataSourceFreshness {
        val days = intervalsStore.fitnessDays(athleteId, date.minusDays(WIDE_LOOKBACK_DAYS), date)
        val latestDataDate = days.maxByOrNull { it.date }?.date
        return DataSourceFreshness(
            source = DataSource.INTERVALS_FITNESS,
            latestDataDate = latestDataDate,
            dataAgeDays = latestDataDate?.let { daysBetween(it, date) },
            storedRecordCount = days.count { !it.date.isBefore(windowStart) }.toLong(),
            collectionTracked = false,
            lastAttemptAt = null,
            lastAttemptOutcome = null,
            lastSuccessfulCollectionAt = null,
            collectionAgeHours = null,
            lastFailureAt = null,
            lastFailureCode = null,
            consecutiveFailureCount = null,
        )
    }

    /**
     * The two archive importers. They are manual, one-off recoveries of history rather than ongoing
     * collection, so they keep no attempt ledger of the kind the Garmin paths do and
     * collectionTracked is false: old data here is expected, not a symptom.
     */
    private fun importedActivities(
        source: DataSource,
        externalSource: ExternalSource,
        athleteId: Long,
        zone: ZoneId,
        date: LocalDate,
        windowActivities: List<Activity>,
    ): DataSourceFreshness {
        val latestDataDate = activities
            .findFirstByAthleteIdAndExternalSourceOrderByStartedAtDesc(athleteId, externalSource)
            .map { it.startedAt.atZone(zone).toLocalDate() }
            .orElse(null)
        return DataSourceFreshness(
            source = source,
            latestDataDate = latestDataDate,
            dataAgeDays = latestDataDate?.let { daysBetween(it, date) },
            storedRecordCount = windowActivities.count { it.externalSource == externalSource }.toLong(),
            collectionTracked = false,
            lastAttemptAt = null,
            lastAttemptOutcome = null,
            lastSuccessfulCollectionAt = null,
            collectionAgeHours = null,
            lastFailureAt = null,
            lastFailureCode = null,
            consecutiveFailureCount = null,
        )
    }

    private fun analysis(windowActivities: List<Activity>): AnalysisFreshness {
        val running = windowActivities.filter {
            it.activityType == ActivityType.RUN || it.activityType == ActivityType.TREADMILL_RUN
        }
        val current = running.count { AnalysisReusePolicy.isCurrent(analysisStore.find(it.id)) }
        return AnalysisFreshness(
            runningActivityCount = running.size,
            analysisCurrent = current,
            analysisMissingOrOutdated = running.size - current,
        )
    }

    private fun daysBetween(from: LocalDate, to: LocalDate): Int = ChronoUnit.DAYS.between(from, to).toInt()

    companion object {
        /** Matches the recovery baseline window, the shortest span any consumer reasons over. */
        const val DEFAULT_WINDOW_DAYS = 28

        const val MAX_WINDOW_DAYS = 365

        /**
         * How far back the latest data date may look. Deliberately independent of - and much wider
         * than - the reported window: a source last updated 40 days ago must report its real age,
         * never a null that reads as "this source has no data at all".
         */
        const val WIDE_LOOKBACK_DAYS = 365L
    }
}
