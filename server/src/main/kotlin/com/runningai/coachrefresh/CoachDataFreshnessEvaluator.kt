package com.runningai.coachrefresh

import com.runningai.activity.Activity
import com.runningai.activity.ActivityType
import com.runningai.activity.detail.ActivityDetailStore
import com.runningai.activity.detail.DetailPartStatus
import com.runningai.activity.detail.DetailPayloadType
import com.runningai.activity.detail.SampleCompleteness
import com.runningai.analysis.ActivityAnalysisStore
import com.runningai.analysis.RUNNING_ANALYSIS_VERSION
import com.runningai.enrichment.IntervalsEnrichmentStore
import com.runningai.recovery.RecoveryRepository
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * One activity's DB-only readiness facts, as judged purely from what is already stored.
 *
 * [sampleStreamStatus] `null` means the sample part was never attempted at all; [EMPTY][DetailPartStatus.EMPTY]
 * is a legitimate "this activity genuinely has no sample stream" outcome (never a failure); only a
 * [NORMALIZED][DetailPartStatus.NORMALIZED] stream carries a real [sampleCompleteness] worth reading.
 */
data class ActivityReadiness(
    val activityId: Long,
    val isRunning: Boolean,
    val detailIncomplete: Boolean,
    val analysisMissingOrOutdated: Boolean,
    val sampleStreamStatus: DetailPartStatus?,
    val sampleCompleteness: SampleCompleteness?,
)

data class FreshnessEvaluation(
    val freshness: CoachDataFreshness,
    val blockers: List<String>,
    val warnings: List<String>,
)

/**
 * Judges coach-data readiness from already-stored rows only (Phase 6H-9 section 27): **zero**
 * network calls. This is deliberately separate from [CoachDataRefreshService]: the network-stage
 * outcomes (did the Garmin summary sync / Intervals fitness / Garmin recovery call itself fail) are
 * facts the orchestrator observes directly from its own calls, while this evaluator answers a
 * different question - given what is now in the database (regardless of why), is it fresh and
 * complete enough for a coach decision?
 *
 * Section 37 (critical): "no recent activity" is read from [CoachDataFreshness.newestActivityDate]
 * alone and is NEVER itself treated as a staleness signal - an athlete can legitimately not have run
 * in days. Whether the *absence* of recent activity can be trusted is a question for the Garmin
 * summary-sync stage (did it run and succeed just now?), not for this evaluator.
 */
@Service
class CoachDataFreshnessEvaluator(
    private val detailStore: ActivityDetailStore,
    private val analysisStore: ActivityAnalysisStore,
    private val intervalsStore: IntervalsEnrichmentStore,
    private val recoveryRepository: RecoveryRepository,
    private val properties: CoachDataRefreshProperties,
) {

    /** One activity's DB-only readiness, independent of whether this refresh touched it. */
    fun activityReadiness(activity: Activity): ActivityReadiness {
        val isRunning = isRunningType(activity.activityType)
        val parts = detailStore.collection(activity.id).associateBy { it.payloadType }
        val detailIncomplete = !REQUIRED_DETAIL_PARTS.all { it in parts } ||
            parts.values.any { it.status == DetailPartStatus.FETCH_FAILED || it.status == DetailPartStatus.MAPPING_FAILED }
        val analysis = analysisStore.find(activity.id)
        val analysisMissingOrOutdated = isRunning &&
            (analysis == null || analysis.analysisVersion != RUNNING_ANALYSIS_VERSION)
        val streamPart = parts[DetailPayloadType.ACTIVITY_DETAILS_STREAM]
        val sampleCompleteness = streamPart?.sampleFidelity?.completeness
        return ActivityReadiness(
            activity.id, isRunning, detailIncomplete, analysisMissingOrOutdated, streamPart?.status, sampleCompleteness,
        )
    }

    /**
     * DB-only freshness + hard-blocker/warning reasons for the whole refresh, given the recent
     * activities' already-computed readiness (see [activityReadiness]), [date] (the coach's own
     * date, athlete-local) and [newestActivityDate] (the orchestrator already has the activity list
     * that produced [activityReadiness], so it is passed in rather than re-derived here).
     *
     * Section 37: [newestActivityDate] being old or null is reported as a plain fact in
     * [CoachDataFreshness] - it is never turned into a blocker or warning by this evaluator, because
     * an activity gap is not evidence of a stale *source*, only evidence that the athlete did not
     * train.
     */
    fun evaluate(
        date: LocalDate,
        athleteId: Long,
        newestActivityDate: LocalDate?,
        activityReadiness: List<ActivityReadiness>,
    ): FreshnessEvaluation {
        val blockers = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val runningActivities = activityReadiness.filter { it.isRunning }
        if (runningActivities.any { it.detailIncomplete }) {
            blockers += "RECENT_RUNNING_ACTIVITY_DETAIL_INCOMPLETE"
        }
        if (runningActivities.any { it.analysisMissingOrOutdated }) {
            blockers += "RECENT_RUNNING_ACTIVITY_ANALYSIS_MISSING"
        }
        if (activityReadiness.any {
                it.sampleStreamStatus == DetailPartStatus.NORMALIZED &&
                    (it.sampleCompleteness == SampleCompleteness.DOWNSAMPLED || it.sampleCompleteness == SampleCompleteness.UNKNOWN)
            }) {
            warnings += "RECENT_ACTIVITY_SAMPLE_NOT_FULL"
        }
        if (activityReadiness.isEmpty()) {
            warnings += "NO_RECENT_ACTIVITY"
        }

        // The lookback used to FIND the latest stored row is deliberately wider than the age
        // threshold used to JUDGE it: a row that exists but is older than the threshold must be
        // reported as "too old" (a blocker), never silently reclassified as "no data at all" (a
        // softer warning) just because it fell outside too-narrow a search window.
        val latestFitness = intervalsStore.fitnessDays(athleteId, date.minusDays(WIDE_LOOKBACK_DAYS), date)
            .maxByOrNull { it.date }
        val fitnessAgeDays = latestFitness?.let { daysSince(date, it.date) }
        if (latestFitness == null) {
            warnings += "NO_RECENT_FITNESS_DATA"
        } else if (fitnessAgeDays!! > properties.maxFitnessAgeDays) {
            blockers += "FITNESS_DATA_TOO_OLD"
        }

        val latestRecovery = recoveryRepository
            .findByAthleteIdAndRecoveryDateBetweenOrderByRecoveryDateAsc(athleteId, date.minusDays(WIDE_LOOKBACK_DAYS), date)
            .maxByOrNull { it.recoveryDate }
        val recoveryAgeDays = latestRecovery?.let { daysSince(date, it.recoveryDate) }
        if (latestRecovery == null) {
            warnings += "NO_RECENT_RECOVERY_DATA"
        } else if (recoveryAgeDays!! > properties.maxRecoveryAgeDays) {
            blockers += "RECOVERY_DATA_TOO_OLD"
        }

        return FreshnessEvaluation(
            freshness = CoachDataFreshness(
                newestActivityDate = newestActivityDate,
                latestFitnessDate = latestFitness?.date,
                fitnessAgeDays = fitnessAgeDays,
                latestRecoveryDate = latestRecovery?.recoveryDate,
                recoveryAgeDays = recoveryAgeDays,
            ),
            blockers = blockers,
            warnings = warnings,
        )
    }

    private fun daysSince(asOf: LocalDate, other: LocalDate): Int = ChronoUnit.DAYS.between(other, asOf).toInt()

    private fun isRunningType(type: ActivityType): Boolean =
        type == ActivityType.RUN || type == ActivityType.TREADMILL_RUN

    companion object {
        /** Search window for the latest stored fitness/recovery row, independent of the age threshold. */
        const val WIDE_LOOKBACK_DAYS = 90L

        val REQUIRED_DETAIL_PARTS = setOf(
            DetailPayloadType.ACTIVITY_LIST,
            DetailPayloadType.ACTIVITY_DETAIL,
            DetailPayloadType.SPLITS,
            DetailPayloadType.HR_ZONES,
            DetailPayloadType.POWER_ZONES,
            DetailPayloadType.ACTIVITY_DETAILS_STREAM,
        )
    }
}
