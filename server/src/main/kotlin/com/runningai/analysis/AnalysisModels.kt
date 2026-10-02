package com.runningai.analysis

import com.runningai.activity.detail.SampleCompleteness
import java.time.Instant

/**
 * Objective evidence derived from one stored activity (Phase 6H-4).
 *
 * The engine measures; it does not judge. Every value here is a number the stored data supports, with no
 * rating, no threshold and no good/bad classification anywhere in this package - interpreting the numbers
 * is the AI coach's job. A metric the data cannot support is `null`, never zero and never an estimate.
 */
const val RUNNING_ANALYSIS_VERSION: String = "RUNNING_ANALYSIS_V1"

/**
 * Whether the stored data could be analysed - not how the session went.
 */
enum class AnalysisStatus {
    /** Every metric applicable to this activity type was computed. */
    COMPLETE,

    /** Some applicable metric could not be computed from the stored data. */
    PARTIAL,

    /** Not enough stored data to compute any session metric. */
    INSUFFICIENT_DATA,
}

/** Halves of the session, split at the elapsed-time midpoint. Null throughout when no split was possible. */
data class HalfSplitMetrics(
    val firstHalfAvgHr: Double? = null,
    val secondHalfAvgHr: Double? = null,
    val hrChangeBpm: Double? = null,
    val hrChangePercent: Double? = null,
    val firstHalfAvgSpeed: Double? = null,
    val secondHalfAvgSpeed: Double? = null,
    val speedChangePercent: Double? = null,
    val firstHalfAvgCadence: Double? = null,
    val secondHalfAvgCadence: Double? = null,
    val cadenceChangeSpm: Double? = null,
    val cadenceChangePercent: Double? = null,
    val firstHalfSpeedHrRatio: Double? = null,
    val secondHalfSpeedHrRatio: Double? = null,
    val speedHrDecouplingPercent: Double? = null,
)

/** What the sample stream supports, before any half is taken. */
data class SessionMetrics(
    val validSampleCount: Int,
    val analysisDurationSeconds: Double? = null,
    val analysisDistanceMeters: Double? = null,
    val halves: HalfSplitMetrics = HalfSplitMetrics(),
)

/** Time in heart-rate zone, as the source reported it. Percentages are of [totalSeconds]. */
data class ZoneExposure(
    val zoneSeconds: Map<Int, Double> = emptyMap(),
    val totalSeconds: Double? = null,
    val zonePercent: Map<Int, Double> = emptyMap(),
)

/** Seconds at or above a share of the athlete's lactate-threshold heart rate. Null without an LTHR. */
data class ThresholdExposure(
    val lthr90Seconds: Double? = null,
    val lthr95Seconds: Double? = null,
    val lthr100Seconds: Double? = null,
)

/** Laps as recorded, across every lap of the activity. */
data class LapMetrics(
    val lapCount: Int = 0,
    val speedMean: Double? = null,
    val speedStdDev: Double? = null,
    val speedCvPercent: Double? = null,
    val hrMean: Double? = null,
    val hrProgression: Double? = null,
    val cadenceMean: Double? = null,
)

/**
 * A run of consecutive laps sharing one `intensityType` + `workoutStepIndex`. The same step index coming
 * back later is a separate block, because that is how a repeat is expressed.
 */
data class WorkoutBlock(
    val intensityType: String?,
    val workoutStepIndex: Int?,
    val firstLapIndex: Int,
    val lastLapIndex: Int,
    /** When the block began, from its first lap. Null when the source reported no lap start time. */
    val startTime: Instant?,
    val durationSeconds: Double?,
    val distanceMeters: Double?,
    val averageSpeed: Double?,
    val averageHr: Double?,
    val maxHr: Double?,
    val averageCadence: Double?,
    val averagePower: Double?,
)

/** One work repetition of an identified group. */
data class IntervalRepetition(
    val repetitionIndex: Int,
    val workoutStepIndex: Int?,
    val firstLapIndex: Int,
    val lastLapIndex: Int,
    val durationSeconds: Double?,
    val distanceMeters: Double?,
    val averageSpeed: Double?,
    val averageHr: Double?,
    val maxHr: Double?,
    val averageCadence: Double?,
    val averagePower: Double?,
)

/**
 * Heart rate across the recovery block that follows a group's last work repetition.
 *
 * **This is not Garmin's Recovery HR metric.** It is the RunningAI interval recovery HR change: the fall
 * from the start of the recovery block to its end, measured over the stored samples.
 */
data class RecoveryHrChange(
    val startHr: Double? = null,
    val endHr: Double? = null,
    val dropBpm: Double? = null,
    val durationSeconds: Double? = null,
)

/** Repeatability of one identified interval group, over its work repetitions only. */
data class IntervalGroup(
    val groupIndex: Int,
    val workoutStepIndex: Int?,
    val repetitions: List<IntervalRepetition>,
    val meanSpeed: Double? = null,
    val speedStdDev: Double? = null,
    val speedCvPercent: Double? = null,
    val firstRepSpeed: Double? = null,
    val lastRepSpeed: Double? = null,
    val lastVsFirstSpeedChangePercent: Double? = null,
    val firstRepHr: Double? = null,
    val lastRepHr: Double? = null,
    val hrProgressionBpm: Double? = null,
    val recovery: RecoveryHrChange = RecoveryHrChange(),
) {
    val workRepCount: Int get() = repetitions.size
}

/** The whole derived result for one activity. */
data class ActivityAnalysis(
    val activityId: Long,
    val analysisVersion: String,
    val status: AnalysisStatus,
    val inputSampleCompleteness: SampleCompleteness?,
    val computedAt: Instant,
    val session: SessionMetrics,
    val zones: ZoneExposure,
    val threshold: ThresholdExposure,
    val laps: LapMetrics,
    val intervalGroups: List<IntervalGroup>,
)
