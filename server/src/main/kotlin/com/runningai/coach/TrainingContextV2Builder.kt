package com.runningai.coach

import com.runningai.activity.Activity
import com.runningai.activity.ActivityRepository
import com.runningai.activity.ActivityType
import com.runningai.activity.detail.ActivityDetailStore
import com.runningai.activity.detail.DetailPayloadType
import com.runningai.activity.detail.SampleCompleteness
import com.runningai.analysis.ActivityAnalysisStore
import com.runningai.athlete.AthleteIntensityProfileService
import com.runningai.athlete.AthleteService
import com.runningai.enrichment.IntervalsEnrichmentStore
import com.runningai.enrichment.IntervalsFitnessDayData
import com.runningai.recovery.RecoveryRepository
import com.runningai.training.TrainingProperties
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Builds [TrainingContextV2] from stored data only (Phase 6H-7). **Pure read**: no Garmin call, no
 * Intervals call, no recovery live sync, no detail refresh and no
 * [com.runningai.analysis.RunningActivityAnalysisService.analyse] recompute — only already-persisted
 * rows are read. An activity without a stored analysis is reported as missing, never recomputed.
 *
 * No look-ahead: every query is bounded so nothing started, matched or recorded after the session
 * date `D` can appear — activities use the athlete-local half-open day range ending at `D+1`
 * (same boundary pattern as `TrainingDecisionContextService`), fitness days are queried with
 * `newest = D`, and the recovery window ends at `D`.
 */
@Service
@Transactional(readOnly = true)
class TrainingContextV2Builder(
    private val athleteService: AthleteService,
    private val profileService: AthleteIntensityProfileService,
    private val recoveryContextBuilder: RecoveryContextBuilder,
    private val recoveryRepository: RecoveryRepository,
    private val activities: ActivityRepository,
    private val detailStore: ActivityDetailStore,
    private val analysisStore: ActivityAnalysisStore,
    private val intervalsStore: IntervalsEnrichmentStore,
    private val trainingProperties: TrainingProperties,
    private val properties: TrainingContextV2Properties,
) {

    fun build(date: LocalDate, constraints: SessionConstraints = SessionConstraints()): TrainingContextV2 {
        val athlete = athleteService.getDefaultAthlete()
        val zone = ZoneId.of(athlete.timezone)
        val profile = profileService.getDefaultProfile()

        val historyStart = date.minusDays(HISTORY_WINDOW_DAYS - 1L)
        val from = historyStart.atStartOfDay(zone).toInstant()
        val toExclusive = date.plusDays(1).atStartOfDay(zone).toInstant() // exclusive: no look-ahead
        val windowActivities = activities
            .findByAthleteIdAndStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAtDesc(athlete.id, from, toExclusive)

        val longRunSeconds = trainingProperties.classification().longRunMinDuration().toSeconds()
        val rhythm = rhythm(date, zone, windowActivities, longRunSeconds)
        val recentEvidence = windowActivities.take(properties.maxRecentActivities).map { evidenceOf(it, zone) }

        val fitnessWindowStart = date.minusDays(FITNESS_WINDOW_DAYS - 1L)
        val fitnessDays = intervalsStore.fitnessDays(athlete.id, fitnessWindowStart, date)
        val trainingLoad = trainingLoad(date, fitnessDays.associateBy { it.date })

        val coverage = DataCoverageV2(
            historyWindowDays = HISTORY_WINDOW_DAYS,
            supportedActivityCount = windowActivities.size,
            analysedActivityCount = windowActivities.count { analysisStore.find(it.id) != null },
            fullSampleActivityCount = windowActivities.count { streamCompleteness(it.id) == SampleCompleteness.FULL },
            intervalsMatchedActivityCount = windowActivities.count { intervalsStore.findMetrics(it.id) != null },
            fitnessWindowDays = FITNESS_WINDOW_DAYS,
            fitnessDaysAvailable = fitnessDays.size,
            recoveryWindowDays = RECOVERY_WINDOW_DAYS,
            recoveryDaysAvailable = recoveryDaysAvailable(athlete.id, date),
            oldestActivityDate = windowActivities.minOfOrNull { it.startedAt.atZone(zone).toLocalDate() },
            newestActivityDate = windowActivities.maxOfOrNull { it.startedAt.atZone(zone).toLocalDate() },
        )

        return TrainingContextV2(
            date = date,
            athlete = AthleteThresholds(profile.lactateThresholdHeartRateBpm(), profile.lactateThresholdPaceSecondsPerKm()),
            dataCoverage = coverage,
            recovery = recoveryContextBuilder.build(date),
            trainingLoad = trainingLoad,
            trainingRhythm = rhythm,
            recentActivities = recentEvidence,
            constraints = constraints,
        )
    }

    private fun rhythm(date: LocalDate, zone: ZoneId, windowActivities: List<Activity>, longRunSeconds: Long): TrainingRhythmV2 {
        val activeDates = windowActivities.map { it.startedAt.atZone(zone).toLocalDate() }.toSet()
        // Bounded by the same 90-day window the activities were queried over: an empty history must
        // report a finite rest streak (the window length), never scan backward without end.
        val historyStart = date.minusDays(HISTORY_WINDOW_DAYS - 1L)
        var consecutiveActive = 0
        var cursor = date
        while (!cursor.isBefore(historyStart) && activeDates.contains(cursor)) {
            consecutiveActive++
            cursor = cursor.minusDays(1)
        }
        var consecutiveRest = 0
        cursor = date
        while (!cursor.isBefore(historyStart) && !activeDates.contains(cursor)) {
            consecutiveRest++
            cursor = cursor.minusDays(1)
        }

        val lastRun = windowActivities.firstOrNull { isRunning(it.activityType) }
        val lastLongRun = windowActivities.firstOrNull { isRunning(it.activityType) && it.durationSeconds >= longRunSeconds }
        val lastStructured = windowActivities.firstOrNull { analysisStore.find(it.id)?.intervalGroups?.isNotEmpty() == true }

        return TrainingRhythmV2(
            consecutiveActiveDays = consecutiveActive,
            consecutiveRestDays = consecutiveRest,
            lastRunDate = lastRun?.startedAt?.atZone(zone)?.toLocalDate(),
            daysSinceLastRun = lastRun?.let { daysSince(date, it.startedAt.atZone(zone).toLocalDate()) },
            lastLongRunDate = lastLongRun?.startedAt?.atZone(zone)?.toLocalDate(),
            daysSinceLastLongRun = lastLongRun?.let { daysSince(date, it.startedAt.atZone(zone).toLocalDate()) },
            structuredIntervalDetectionAvailable = true,
            lastStructuredIntervalDate = lastStructured?.startedAt?.atZone(zone)?.toLocalDate(),
            daysSinceLastStructuredInterval = lastStructured?.let { daysSince(date, it.startedAt.atZone(zone).toLocalDate()) },
        )
    }

    private fun evidenceOf(activity: Activity, zone: ZoneId): RecentActivityEvidence {
        val detail = detailStore.detail(activity.id)
        val analysis = analysisStore.find(activity.id)
        val metrics = intervalsStore.findMetrics(activity.id)

        val facts = RecentActivityFacts(
            date = activity.startedAt.atZone(zone).toLocalDate(),
            activityType = activity.activityType,
            durationSeconds = activity.durationSeconds,
            distanceMeters = activity.distanceMeters,
            averageHeartRateBpm = activity.averageHeartRate,
            maxHeartRateBpm = activity.maxHeartRate,
            averageCadence = detail?.averageRunningCadenceSpm,
            averagePower = detail?.averagePower,
        )
        val runningAiEvidence = analysis?.let { a ->
            RunningAiAnalysisEvidence(
                hrChangePercent = a.session.halves.hrChangePercent,
                speedChangePercent = a.session.halves.speedChangePercent,
                speedHrDecouplingPercent = a.session.halves.speedHrDecouplingPercent,
                cadenceChangePercent = a.session.halves.cadenceChangePercent,
                lthr90Seconds = a.threshold.lthr90Seconds,
                lthr95Seconds = a.threshold.lthr95Seconds,
                lthr100Seconds = a.threshold.lthr100Seconds,
                lapSpeedCvPercent = a.laps.speedCvPercent,
                heartRateZonePercent = a.zones.zonePercent,
                totalZoneSeconds = a.zones.totalSeconds,
                intervalGroups = a.intervalGroups.take(properties.maxIntervalGroupsPerActivity).map { g ->
                    IntervalGroupEvidence(
                        workRepCount = g.workRepCount,
                        meanSpeed = g.meanSpeed,
                        speedCvPercent = g.speedCvPercent,
                        lastVsFirstSpeedChangePercent = g.lastVsFirstSpeedChangePercent,
                        hrProgressionBpm = g.hrProgressionBpm,
                        recoveryHrDropBpm = g.recovery.dropBpm,
                        recoveryDurationSeconds = g.recovery.durationSeconds,
                    )
                },
            )
        }
        val intervalsEvidence = metrics?.let {
            IntervalsActivityEvidence(it.trainingLoad, it.intensity, it.ctlAfterActivity, it.atlAfterActivity)
        }
        val dataQuality = ActivityDataQuality(
            sampleCompleteness = streamCompleteness(activity.id),
            analysisStatus = analysis?.status,
            analysisVersion = analysis?.analysisVersion,
        )
        return RecentActivityEvidence(facts, runningAiEvidence, intervalsEvidence, dataQuality)
    }

    private fun streamCompleteness(activityId: Long): SampleCompleteness? =
        detailStore.part(activityId, DetailPayloadType.ACTIVITY_DETAILS_STREAM)?.sampleFidelity?.completeness

    private fun trainingLoad(date: LocalDate, byDate: Map<LocalDate, IntervalsFitnessDayData>): TrainingLoadContextV2 {
        val current = (0 until FITNESS_WINDOW_DAYS)
            .asSequence()
            .map { date.minusDays(it.toLong()) }
            .firstNotNullOfOrNull { byDate[it] }
        return TrainingLoadContextV2(
            sourceDate = current?.date,
            ageDays = current?.let { daysSince(date, it.date) },
            ctl = current?.ctl,
            atl = current?.atl,
            derivedForm = current?.derivedForm,
            rampRate = current?.rampRate,
            ctlLoad = current?.ctlLoad,
            atlLoad = current?.atlLoad,
            sevenDaysAgo = byDate[date.minusDays(7)]?.let { FitnessSnapshotV2(it.date, it.ctl, it.atl, it.derivedForm) },
            twentyEightDaysAgo = byDate[date.minusDays(28)]?.let { FitnessSnapshotV2(it.date, it.ctl, it.atl, it.derivedForm) },
        )
    }

    private fun recoveryDaysAvailable(athleteId: Long, date: LocalDate): Int {
        val from = date.minusDays(RECOVERY_WINDOW_DAYS - 1L)
        return recoveryRepository.findByAthleteIdAndRecoveryDateBetweenOrderByRecoveryDateAsc(athleteId, from, date).size
    }

    private fun daysSince(asOf: LocalDate, other: LocalDate): Int = ChronoUnit.DAYS.between(other, asOf).toInt()

    private fun isRunning(type: ActivityType): Boolean = type == ActivityType.RUN || type == ActivityType.TREADMILL_RUN

    companion object {
        const val HISTORY_WINDOW_DAYS = 90
        const val FITNESS_WINDOW_DAYS = 90
        const val RECOVERY_WINDOW_DAYS = 28
    }
}
