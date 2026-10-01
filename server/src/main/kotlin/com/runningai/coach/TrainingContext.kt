package com.runningai.coach

import com.runningai.training.CandidateTrainingType
import java.time.LocalDate

/**
 * Everything the AI coach is given about the athlete, as a deterministic, provider-neutral snapshot.
 *
 * Two rules hold throughout this model and are relied on by the prompt:
 *  - a `null` value means **genuinely unknown**, never "assume a default"; and
 *  - nothing here is a raw external payload. No GPS, no credential, no token, no Garmin/Intervals
 *    response body, no athlete identity. Only derived training numbers reach the model.
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
 * Recovery/readiness metrics.
 *
 * **Every field is null in this build.** HRV, sleep, resting heart rate, Body Battery and stress
 * are not ingested anywhere in RunningAI: the Garmin connector exposes only `/health`,
 * `/activities` and `/lactate-threshold`, and no table stores them. They are modelled here so the
 * contract is stable once ingestion lands, and are emitted as explicit JSON nulls so the coach can
 * see that the data is missing instead of silently reasoning as if recovery were fine.
 */
data class RecoveryContext(
    val hrvMs: Double? = null,
    val restingHeartRateBpm: Int? = null,
    val sleepHours: Double? = null,
    val bodyBattery: Int? = null,
    val stressLevel: Int? = null,
) {
    /** True when no recovery metric at all is available, which the prompt states explicitly. */
    @get:com.fasterxml.jackson.annotation.JsonIgnore
    val anyAvailable: Boolean
        get() = hrvMs != null || restingHeartRateBpm != null || sleepHours != null ||
            bodyBattery != null || stressLevel != null
}

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
