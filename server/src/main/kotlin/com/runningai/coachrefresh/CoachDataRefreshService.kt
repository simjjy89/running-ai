package com.runningai.coachrefresh

import com.runningai.activity.Activity
import com.runningai.activity.ActivityRepository
import com.runningai.activity.ActivityType
import com.runningai.activity.detail.DetailCollectionOutcome
import com.runningai.activity.detail.DetailPartStatus
import com.runningai.activity.detail.SampleCompleteness
import com.runningai.analysis.RunningActivityAnalysisService
import com.runningai.athlete.AthleteService
import com.runningai.integration.garmin.GarminActivityDetailIngestionService
import com.runningai.integration.garmin.GarminConnectorException
import com.runningai.integration.garmin.GarminDetailCollectionAlreadyRunningException
import com.runningai.integration.garmin.GarminIncrementalSyncException
import com.runningai.integration.garmin.GarminRecoverySyncAlreadyRunningException
import com.runningai.integration.garmin.GarminRecoverySyncService
import com.runningai.integration.garmin.GarminSyncAlreadyRunningException
import com.runningai.integration.garmin.GarminSyncOperationService
import com.runningai.integration.intervals.IntervalsEnrichmentAlreadyRunningException
import com.runningai.integration.intervals.IntervalsEnrichmentService
import com.runningai.integration.intervals.IntervalsException
import com.runningai.integration.intervals.MatchStatus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.locks.ReentrantLock

/**
 * Keeps the RunningAI DB current just before the AI coach reads it (Phase 6H-9).
 *
 * Orchestration only: every external call goes through an existing, already-safe service
 * (`GarminSyncOperationService`, `GarminActivityDetailIngestionService`,
 * `RunningActivityAnalysisService`, `IntervalsEnrichmentService`, `GarminRecoverySyncService`) - no
 * new Garmin/Intervals transport is written here, and nothing here can write a workout: there is no
 * dependency on `IntervalsWorkoutPublisher`/`IntervalsWorkoutClient` or any Garmin write path.
 *
 * Single-flight in this JVM (own lock - never delegates to a sub-service's lock, which stays in
 * place as its own independent guard). No stage is retried; an account-level failure (401/403/429/
 * timeout/connection) stops that stage immediately and is reported, never hidden. A stage failure
 * never throws out of [refresh] - it always returns a [CoachDataRefreshResult] (`readyForCoach`
 * reports whether a *new* coach draft should be generated from what is now in the DB), except for
 * [CoachDataRefreshAlreadyRunningException] on a concurrent call.
 */
@Service
class CoachDataRefreshService(
    private val athleteService: AthleteService,
    private val activities: ActivityRepository,
    private val garminSyncOperationService: GarminSyncOperationService,
    private val detailIngestion: GarminActivityDetailIngestionService,
    private val analysisService: RunningActivityAnalysisService,
    private val intervalsEnrichmentService: IntervalsEnrichmentService,
    private val garminRecoverySyncService: GarminRecoverySyncService,
    private val freshnessEvaluator: CoachDataFreshnessEvaluator,
    private val properties: CoachDataRefreshProperties,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(CoachDataRefreshService::class.java)
    private val lock = ReentrantLock()

    fun refresh(requestedDate: LocalDate?): CoachDataRefreshResult {
        if (!lock.tryLock()) {
            log.info("Coach data refresh rejected: another refresh is running")
            throw CoachDataRefreshAlreadyRunningException()
        }
        try {
            val athlete = athleteService.getDefaultAthlete()
            val zone = ZoneId.of(athlete.timezone)
            val date = requestedDate ?: LocalDate.now(clock.withZone(zone))
            log.info("Coach data refresh started: date={}", date)

            val garminSummary = runGarminSummarySync()
            if (!garminSummary.success) {
                return notReady(date, garminSummary, "GARMIN_SUMMARY_SYNC_FAILED")
            }

            val windowStart = date.minusDays(properties.recentActivityDays - 1L)
            val from = windowStart.atStartOfDay(zone).toInstant()
            val toExclusive = date.plusDays(1).atStartOfDay(zone).toInstant()
            val recentActivities = activities
                .findByAthleteIdAndStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAtDesc(athlete.id, from, toExclusive)
            val newestActivityDate = recentActivities.maxOfOrNull { it.startedAt.atZone(zone).toLocalDate() }

            val activitySummary = completeRecentActivities(recentActivities)
            val intervalsFitness = refreshIntervalsFitness(athlete.id, date)
            val recovery = refreshRecovery(date)

            val readiness = recentActivities.map { freshnessEvaluator.activityReadiness(it) }
            val evaluation = freshnessEvaluator.evaluate(date, athlete.id, newestActivityDate, readiness)

            val reasons = mutableListOf<String>()
            reasons += evaluation.blockers
            reasons += evaluation.warnings
            if (intervalsFitness.failed) reasons += "INTERVALS_FITNESS_REFRESH_FAILED"
            if (recovery.failed) reasons += "GARMIN_RECOVERY_SYNC_FAILED"
            if (activitySummary.intervalsFailureCode != null) reasons += "INTERVALS_ACTIVITY_ENRICHMENT_FAILED:${activitySummary.intervalsFailureCode}"

            val readyForCoach = evaluation.blockers.isEmpty() && !intervalsFitness.failed && !recovery.failed

            val result = CoachDataRefreshResult(
                date = date,
                readyForCoach = readyForCoach,
                reasons = reasons,
                garmin = garminSummary,
                activities = activitySummary,
                intervalsFitness = intervalsFitness,
                recovery = recovery,
                freshness = evaluation.freshness,
            )
            log.info("Coach data refresh finished: date={} readyForCoach={} reasons={}", date, readyForCoach, reasons)
            return result
        } finally {
            lock.unlock()
        }
    }

    private fun notReady(date: LocalDate, garmin: GarminRefreshSummary, reason: String): CoachDataRefreshResult {
        log.info("Coach data refresh stopped: date={} reason={}", date, reason)
        return CoachDataRefreshResult(
            date = date,
            readyForCoach = false,
            reasons = listOf(reason),
            garmin = garmin,
            activities = EMPTY_ACTIVITY_SUMMARY,
            intervalsFitness = EMPTY_FITNESS_SUMMARY,
            recovery = EMPTY_RECOVERY_SUMMARY,
            freshness = CoachDataFreshness(null, null, null, null, null),
        )
    }

    private fun runGarminSummarySync(): GarminRefreshSummary {
        return try {
            val r = garminSyncOperationService.runSync()
            GarminRefreshSummary(
                success = true,
                lastSuccessfulSyncAt = r.lastSuccessfulSyncAt(),
                syncAgeMinutes = r.lastSuccessfulSyncAt()?.let { ageMinutes(it) },
                fetched = r.fetched(),
                created = r.created(),
                updated = r.updated(),
                failed = r.failed(),
                checkpointAdvanced = r.checkpointAdvanced(),
                failureCode = null,
            )
        } catch (e: GarminSyncAlreadyRunningException) {
            // A concurrent MANUAL sync (different lock than ours) is already running: this is a
            // transient collision, not a reason to call the DB stale - report it plainly.
            failedGarminSummary("GARMIN_SYNC_ALREADY_RUNNING")
        } catch (e: GarminConnectorException) {
            log.warn("Coach data refresh: Garmin summary sync failed reason={}", e.reason)
            failedGarminSummary("GARMIN_${e.reason.name}")
        } catch (e: GarminIncrementalSyncException) {
            failedGarminSummary("INCREMENTAL_WINDOW_INCOMPLETE")
        }
    }

    private fun failedGarminSummary(code: String) =
        GarminRefreshSummary(false, null, null, 0, 0, 0, 0, false, code)

    /** Detail completion + running analysis + Intervals activity enrichment, in that order per activity. */
    private fun completeRecentActivities(recentActivities: List<Activity>): RecentActivityRefreshSummary {
        var detailComplete = 0
        var detailCollected = 0
        var detailFailed = 0
        var analysisCurrent = 0
        var analysisComputed = 0
        var analysisMissing = 0
        var fullSamples = 0
        var noSampleStreams = 0
        var sampleIncomplete = 0
        var matched = 0
        var unmatched = 0
        var ambiguous = 0
        var intervalsFailureCode: String? = null

        for (activity in recentActivities) {
            val isRunning = activity.activityType == ActivityType.RUN || activity.activityType == ActivityType.TREADMILL_RUN
            val before = freshnessEvaluator.activityReadiness(activity)
            var detailChangedThisRun = false

            if (before.detailIncomplete) {
                try {
                    val outcome = detailIngestion.collect(activity.externalId)
                    detailChangedThisRun = true
                    if (outcome.outcome == DetailCollectionOutcome.COMPLETE) detailCollected++ else detailFailed++
                } catch (e: GarminDetailCollectionAlreadyRunningException) {
                    // Another request is already collecting this exact activity; leave it be this run.
                } catch (e: GarminConnectorException) {
                    log.warn("Coach data refresh: detail collection failed activityId={} reason={}", activity.id, e.reason)
                    detailFailed++
                }
            } else {
                detailComplete++
            }

            val after = freshnessEvaluator.activityReadiness(activity)
            when {
                after.sampleStreamStatus == null -> Unit // never attempted; not counted in any bucket
                after.sampleStreamStatus == DetailPartStatus.EMPTY -> noSampleStreams++
                after.sampleCompleteness == SampleCompleteness.FULL -> fullSamples++
                else -> sampleIncomplete++
            }

            if (isRunning) {
                val needsAnalysis = after.analysisMissingOrOutdated || detailChangedThisRun
                if (needsAnalysis) {
                    try {
                        analysisService.analyse(activity.id)
                        analysisComputed++
                    } catch (e: Exception) {
                        log.warn("Coach data refresh: analysis failed activityId={}", activity.id, e)
                        analysisMissing++
                    }
                } else {
                    analysisCurrent++
                }
            }

            if (intervalsFailureCode == null) {
                try {
                    val result = intervalsEnrichmentService.enrichActivity(activity.id)
                    when (result.matchStatus) {
                        MatchStatus.MATCHED -> matched++
                        MatchStatus.UNMATCHED -> unmatched++
                        MatchStatus.AMBIGUOUS -> ambiguous++
                    }
                } catch (e: IntervalsEnrichmentAlreadyRunningException) {
                    // Collision with a concurrent manual enrichment of this same activity; skip for this run.
                } catch (e: IntervalsException) {
                    if (e.reason in STAGE_FATAL_INTERVALS_REASONS) {
                        log.warn("Coach data refresh: Intervals activity enrichment stopped reason={}", e.reason)
                        intervalsFailureCode = e.reason.name
                    } else {
                        log.warn("Coach data refresh: Intervals activity enrichment failed activityId={} reason={}", activity.id, e.reason)
                    }
                }
            }
        }

        return RecentActivityRefreshSummary(
            recentActivityCount = recentActivities.size,
            recentRunningActivityCount = recentActivities.count {
                it.activityType == ActivityType.RUN || it.activityType == ActivityType.TREADMILL_RUN
            },
            detailComplete = detailComplete,
            detailCollected = detailCollected,
            detailFailed = detailFailed,
            analysisCurrent = analysisCurrent,
            analysisComputed = analysisComputed,
            analysisMissing = analysisMissing,
            fullSamples = fullSamples,
            noSampleStreams = noSampleStreams,
            sampleIncomplete = sampleIncomplete,
            intervalsMatched = matched,
            intervalsUnmatched = unmatched,
            intervalsAmbiguous = ambiguous,
            intervalsFailureCode = intervalsFailureCode,
        )
    }

    private fun refreshIntervalsFitness(athleteId: Long, date: LocalDate): IntervalsFitnessRefreshSummary {
        val oldest = date.minusDays(properties.fitnessRefreshDays - 1L)
        return try {
            val r = intervalsEnrichmentService.enrichFitness(oldest, date)
            IntervalsFitnessRefreshSummary(r.oldest, r.newest, r.daysFetched, r.daysStored, false, null)
        } catch (e: IntervalsEnrichmentAlreadyRunningException) {
            IntervalsFitnessRefreshSummary(oldest, date, null, null, true, "INTERVALS_FITNESS_ALREADY_RUNNING")
        } catch (e: IntervalsException) {
            log.warn("Coach data refresh: Intervals fitness refresh failed reason={}", e.reason)
            IntervalsFitnessRefreshSummary(oldest, date, null, null, true, e.reason.name)
        }
    }

    private fun refreshRecovery(date: LocalDate): RecoveryRefreshSummary {
        return try {
            val r = garminRecoverySyncService.backfill(date, properties.recoveryRefreshDays)
            if (!r.completed()) {
                log.warn("Coach data refresh: Garmin recovery refresh stopped early at={} reason={}", r.stoppedAt(), r.stoppedReason())
                RecoveryRefreshSummary(r.requestedDays(), r.updatedDays(), false, true, "GARMIN_${r.stoppedReason()}")
            } else {
                RecoveryRefreshSummary(r.requestedDays(), r.updatedDays(), true, false, null)
            }
        } catch (e: GarminRecoverySyncAlreadyRunningException) {
            RecoveryRefreshSummary(0, 0, false, true, "GARMIN_RECOVERY_SYNC_ALREADY_RUNNING")
        } catch (e: GarminConnectorException) {
            log.warn("Coach data refresh: Garmin recovery refresh failed reason={}", e.reason)
            RecoveryRefreshSummary(0, 0, false, true, "GARMIN_${e.reason.name}")
        }
    }

    private fun ageMinutes(instant: Instant): Long = Duration.between(instant, Instant.now(clock)).toMinutes()

    private companion object {
        val STAGE_FATAL_INTERVALS_REASONS = setOf(
            IntervalsException.Reason.AUTH_FAILED,
            IntervalsException.Reason.FORBIDDEN,
            IntervalsException.Reason.RATE_LIMITED,
            IntervalsException.Reason.TIMEOUT,
            IntervalsException.Reason.CONNECTION_FAILED,
            IntervalsException.Reason.NOT_CONFIGURED,
        )

        val EMPTY_ACTIVITY_SUMMARY = RecentActivityRefreshSummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null)
        val EMPTY_FITNESS_SUMMARY = IntervalsFitnessRefreshSummary(null, null, null, null, false, null)
        val EMPTY_RECOVERY_SUMMARY = RecoveryRefreshSummary(0, 0, false, false, null)
    }
}
