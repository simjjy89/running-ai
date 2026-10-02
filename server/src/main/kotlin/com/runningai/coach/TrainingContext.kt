package com.runningai.coach

import com.fasterxml.jackson.annotation.JsonInclude
import com.runningai.recovery.BaselineStatus
import com.runningai.training.CandidateTrainingType
import java.time.LocalDate

/**
 * Everything the AI coach is given about the athlete, as a deterministic, provider-neutral snapshot.
 *
 * Two rules hold throughout this model and are relied on by the prompt:
 *  - a `null` value means **genuinely unknown**, never "assume a default"; and
 *  - nothing here is a raw external payload. No GPS, no credential, no token, no Garmin/Intervals
 *    response body, no athlete identity. Only derived training and recovery numbers reach the model.
 */
data class TrainingContext(
    val date: LocalDate,
    val athlete: AthleteThresholds,
    val recentTraining: RecentTraining,
    val recovery: RecoveryContext,
    val weeklyContext: WeeklyContext,
    val constraints: SessionConstraints,
)

/**
 * The athlete's current running threshold profile (Phase 6D keeps it in sync with Garmin). Both
 * values are independently optional: a fresh athlete has neither, and the coach must then design
 * qualitatively rather than invent numbers.
 */
data class AthleteThresholds(
    val lactateThresholdHeartRateBpm: Int?,
    val lactateThresholdPaceSecondsPerKm: Int?,
)

/**
 * Recent training history, taken from the existing training-load / decision-context pipeline
 * (the same numbers `/api/v1/training-decision-context` serves). Nothing here is recomputed
 * independently for the coach.
 */
data class RecentTraining(
    val dailyPattern: List<TrainingDay>,
    val lastRunDate: LocalDate?,
    val daysSinceLastRun: Int?,
    val lastLongRunDate: LocalDate?,
    val daysSinceLastLongRun: Int?,
    /**
     * False in this build: the pipeline cannot detect a quality/interval session from the
     * normalised activity fields, so [lastQualityDate] is always null and the coach is told the
     * detection itself is unavailable rather than being allowed to read "no quality recently".
     */
    val qualityDetectionAvailable: Boolean,
    val lastQualityDate: LocalDate?,
    val daysSinceLastQuality: Int?,
    val consecutiveActiveDays: Int,
    val consecutiveRestDays: Int,
    val candidateTrainingTypes: List<CandidateTrainingType>,
)

/** One athlete-local day of recent training. */
data class TrainingDay(
    val date: LocalDate,
    val classification: String,
    val activityCount: Int,
    val loadMinutes: Double,
    val runningDurationSeconds: Long,
    val runningDistanceMeters: Double,
    val cyclingDurationSeconds: Long,
)

/**
 * Garmin recovery metrics (Phase 6F), each compared with the athlete's own recent history.
 *
 * Measurement only: RunningAI reports the latest value, the personal baseline and the difference,
 * and never says whether that is good or bad or what to train because of it. The coach decides.
 *
 * A metric group is `null` when Garmin has no reading for it within the lookup window (no watch
 * worn overnight, never synced, ...). Nothing is ever estimated or defaulted to fill a gap.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class RecoveryContext(
    val hrv: HrvRecovery? = null,
    val sleep: SleepRecovery? = null,
    val restingHeartRate: RestingHeartRateRecovery? = null,
    val bodyBattery: BodyBatteryRecovery? = null,
    val stress: StressRecovery? = null,
) {
    /** True when at least one recovery metric is available. */
    @get:com.fasterxml.jackson.annotation.JsonIgnore
    val anyAvailable: Boolean
        get() = hrv != null || sleep != null || restingHeartRate != null || bodyBattery != null || stress != null
}

/**
 * One metric's latest value against the athlete's personal baseline.
 *
 * - [date]/[ageDays]: when the value was recorded; `ageDays` 0 means it belongs to the session date,
 *   larger numbers mean the value is that many days old.
 * - [baseline]: mean of the valid values in the [baselineWindowDays] days before [date].
 * - [baselineStatus] INSUFFICIENT_DATA: fewer than [minimumSamples] valid days, so [baseline],
 *   [difference] and [differencePercent] are null. The current value is still real.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class RecoveryMeasurement(
    val date: LocalDate,
    val ageDays: Int,
    val current: Double,
    val baseline: Double?,
    val difference: Double?,
    val differencePercent: Double?,
    val sampleCount: Int,
    val baselineWindowDays: Int,
    val minimumSamples: Int,
    val baselineStatus: BaselineStatus,
)

/**
 * Overnight HRV in milliseconds. [garminWeeklyAvgMs] and [garminHrvStatus] are Garmin's own figures
 * for the same night, passed through verbatim (RunningAI does not compute or interpret them).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class HrvRecovery(
    val lastNightAvgMs: RecoveryMeasurement,
    val garminWeeklyAvgMs: Double?,
    val garminHrvStatus: String?,
)

/** Sleep duration in hours and Garmin's overall sleep score (0-100); either may be missing. */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class SleepRecovery(
    val durationHours: RecoveryMeasurement?,
    val sleepScore: RecoveryMeasurement?,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class RestingHeartRateRecovery(
    val bpm: RecoveryMeasurement,
)

/**
 * Garmin Body Battery (0-100). [highest] is the day's highest sampled level; [lowest], [charged] and
 * [drained] are from the same day. For the current day these are intraday values so far.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class BodyBatteryRecovery(
    val highest: RecoveryMeasurement,
    val lowest: Int?,
    val charged: Int?,
    val drained: Int?,
)

/** Garmin all-day stress (0-100): the day's average against baseline, plus the day's maximum. */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class StressRecovery(
    val average: RecoveryMeasurement,
    val max: Int?,
)

/** Rolling-window training load, straight from the existing `TrainingState` computation. */
data class WeeklyContext(
    val acuteLoadMinutes: Double,
    val chronicLoadMinutes: Double,
    val acuteChronicRatio: Double?,
    val current7DayLoadMinutes: Double,
    val previous7DayLoadMinutes: Double,
    val weeklyLoadChangePercent: Double?,
    val running7DayDistanceMeters: Double,
    val running7DayDurationSeconds: Long,
    val rampLoadMinutes: Double,
    val monotony: Double?,
    val strain: Double?,
    val activeDays7Days: Int,
    val restDays7Days: Int,
    val loadTrend: String,
)

/** Where the session will happen. */
enum class TrainingEnvironment {
    OUTDOOR,
    TREADMILL,
    INDOOR,
}

/**
 * What the athlete asked for today. All optional: the first "just make me a workout" call carries
 * none of it.
 *
 * [painOrFatigueFeedback] is deliberately separate from [userFeedback]: the prompt instructs the
 * coach that it may never be ignored or overridden by a training goal.
 */
data class SessionConstraints(
    val availableMinutes: Int? = null,
    val environment: TrainingEnvironment? = null,
    val userFeedback: String? = null,
    val requestedGoal: String? = null,
    val painOrFatigueFeedback: String? = null,
)
