package com.runningai.coach

import com.runningai.athlete.AthleteIntensityProfileService
import com.runningai.training.TrainingDecisionContextService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * Turns what RunningAI actually knows into a [TrainingContext] for the AI coach.
 *
 * Every number comes from an existing, already-tested pipeline:
 *  - [TrainingDecisionContextService] for recent pattern, last-event dates, consecutive counters,
 *    load trend, candidates and the whole rolling-window training state; and
 *  - [AthleteIntensityProfileService] for the athlete's current LTHR and threshold pace (which
 *    Phase 6D keeps in sync with Garmin); and
 *  - [RecoveryContextBuilder] for Garmin recovery metrics against the athlete's own baseline
 *    (Phase 6F).
 *
 * Nothing is recomputed here and nothing is invented: a metric without data stays null (see
 * [RecoveryContext]), so the coach is told it is unknown rather than being handed a
 * plausible-looking default.
 */
@Service
@Transactional(readOnly = true)
class TrainingContextBuilder(
    private val decisionContextService: TrainingDecisionContextService,
    private val profileService: AthleteIntensityProfileService,
    private val recoveryContextBuilder: RecoveryContextBuilder,
) {

    fun today(): LocalDate = decisionContextService.today()

    fun build(date: LocalDate, constraints: SessionConstraints = SessionConstraints()): TrainingContext {
        val decision = decisionContextService.context(date)
        val profile = profileService.getDefaultProfile()
        val state = decision.trainingState()

        return TrainingContext(
            date = date,
            athlete = AthleteThresholds(
                lactateThresholdHeartRateBpm = profile.lactateThresholdHeartRateBpm(),
                lactateThresholdPaceSecondsPerKm = profile.lactateThresholdPaceSecondsPerKm(),
            ),
            recentTraining = RecentTraining(
                dailyPattern = decision.recentPattern().map {
                    TrainingDay(
                        date = it.date(),
                        classification = it.classification().name,
                        activityCount = it.activityCount(),
                        loadMinutes = it.totalLoadMinutes(),
                        runningDurationSeconds = it.runningDurationSeconds(),
                        runningDistanceMeters = it.runningDistanceMeters(),
                        cyclingDurationSeconds = it.cyclingDurationSeconds(),
                    )
                },
                lastRunDate = decision.lastRunningDate(),
                daysSinceLastRun = decision.daysSinceRunning(),
                lastLongRunDate = decision.lastLongRunDate(),
                daysSinceLastLongRun = decision.daysSinceLongRun(),
                qualityDetectionAvailable = decision.qualityDetectionAvailable(),
                lastQualityDate = decision.lastQualityDate(),
                daysSinceLastQuality = decision.daysSinceQuality(),
                consecutiveActiveDays = decision.consecutiveActiveDays(),
                consecutiveRestDays = decision.consecutiveRestDays(),
                candidateTrainingTypes = decision.candidateTrainingTypes(),
            ),
            recovery = recoveryContextBuilder.build(date),
            weeklyContext = WeeklyContext(
                acuteLoadMinutes = state.acuteLoad(),
                chronicLoadMinutes = state.chronicLoad(),
                acuteChronicRatio = state.acuteChronicRatio(),
                current7DayLoadMinutes = state.current7DayLoad(),
                previous7DayLoadMinutes = state.previous7DayLoad(),
                weeklyLoadChangePercent = state.weeklyLoadChangePercent(),
                running7DayDistanceMeters = state.runningDistance7DaysMeters(),
                running7DayDurationSeconds = state.runningDuration7DaysSeconds(),
                rampLoadMinutes = state.rampLoad(),
                monotony = state.monotony(),
                strain = state.strain(),
                activeDays7Days = state.activeDays7Days(),
                restDays7Days = state.restDays7Days(),
                loadTrend = decision.loadTrend().name,
            ),
            constraints = constraints,
        )
    }
}
