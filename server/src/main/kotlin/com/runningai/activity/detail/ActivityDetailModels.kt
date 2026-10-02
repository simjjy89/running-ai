package com.runningai.activity.detail

import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.Instant

/**
 * The kinds of source payload behind an activity's detail (Phase 6H-1A).
 *
 * [ACTIVITY_LIST] is the activity-list item, which stays in `activity_raw` (V3) and is never copied
 * into `activity_raw_payload`; it is listed here because the normalised [ActivityDetailData] is
 * derived from it and collection status is tracked for it like for every other part.
 */
enum class DetailPayloadType {
    ACTIVITY_LIST,
    ACTIVITY_DETAIL,
    SPLITS,
    HR_ZONES,
    POWER_ZONES,
    ACTIVITY_DETAILS_STREAM,
}

enum class ZoneType { HEART_RATE, POWER }

/**
 * Outcome of one part in the last collection of an activity's detail (`activity_detail_collection`).
 * Failures are recorded, never hidden behind an already stored summary activity.
 */
enum class DetailPartStatus {
    /** Fetched, raw stored, normalised rows written. */
    NORMALIZED,

    /** Fetched and raw stored; deliberately not normalised (yet). */
    RAW_STORED,

    /** Fetched and raw stored; the source reported no data for this part. */
    EMPTY,

    /** The request failed; nothing stored for this part in this run. */
    FETCH_FAILED,

    /** Raw stored, normalisation failed; the previous normalised rows are left untouched. */
    MAPPING_FAILED,
    ;

    val failed: Boolean get() = this == FETCH_FAILED || this == MAPPING_FAILED
}

/** Overall result of one collection run. */
enum class DetailCollectionOutcome {
    /** Every attempted part succeeded and none was skipped. */
    COMPLETE,

    /** Some parts succeeded, some failed or were not attempted. */
    PARTIAL,

    /** No part succeeded. */
    FAILED,
}

/**
 * Activity-level metrics, provider-neutral. Every field is nullable: absent means the source did not
 * report it, and nothing is ever estimated. Values are kept exactly as reported (no unit conversion,
 * no rounding); units are stated only where confirmed (see the column names in V12).
 */
data class ActivityDetailData(
    val sourcePayloadType: DetailPayloadType,
    val durationSeconds: Double? = null,
    val elapsedDurationSeconds: Double? = null,
    val movingDurationSeconds: Double? = null,
    val distanceMeters: Double? = null,
    val averageSpeed: Double? = null,
    val maxSpeed: Double? = null,
    val averageHeartRateBpm: Double? = null,
    val maxHeartRateBpm: Double? = null,
    val averageRunningCadenceSpm: Double? = null,
    val maxRunningCadenceSpm: Double? = null,
    val elevationGain: Double? = null,
    val elevationLoss: Double? = null,
    val calories: Double? = null,
    val averagePower: Double? = null,
    val maxPower: Double? = null,
    val normalizedPower: Double? = null,
    val aerobicTrainingEffect: Double? = null,
    val anaerobicTrainingEffect: Double? = null,
    val trainingLoad: Double? = null,
    val trainingEffectLabel: String? = null,
)

/**
 * One lap / split. [extraMetrics] keeps every source field that has no column, by its source key.
 *
 * [intensityType], [workoutIndex] and [workoutStepIndex] describe how the lap sits inside a structured
 * workout (Phase 6H-4). [intensityType] stays a plain string on purpose: the vocabulary is the source's
 * (live Garmin: WARMUP / ACTIVE / RECOVERY / COOLDOWN), and an unseen value must widen the data rather
 * than break ingestion. A lap is not 1:1 with a workout step - several laps can share one step index,
 * and a lap can carry none at all.
 */
data class LapData(
    val lapIndex: Int,
    val startTime: Instant? = null,
    val durationSeconds: Double? = null,
    val elapsedDurationSeconds: Double? = null,
    val movingDurationSeconds: Double? = null,
    val distanceMeters: Double? = null,
    val averageSpeed: Double? = null,
    val maxSpeed: Double? = null,
    val averageHeartRateBpm: Double? = null,
    val maxHeartRateBpm: Double? = null,
    val averageCadence: Double? = null,
    val maxCadence: Double? = null,
    val averagePower: Double? = null,
    val maxPower: Double? = null,
    val elevationGain: Double? = null,
    val elevationLoss: Double? = null,
    val calories: Double? = null,
    val intensityType: String? = null,
    val workoutIndex: Int? = null,
    val workoutStepIndex: Int? = null,
    val extraMetrics: ObjectNode? = null,
)

/** Time spent in one zone. [maxValue] stays null unless the source reports it (never derived). */
data class ZoneData(
    val zoneType: ZoneType,
    val zoneNumber: Int,
    val minValue: Double? = null,
    val maxValue: Double? = null,
    val durationSeconds: Double? = null,
)

/**
 * One sample at the source's own sampling interval. [extraMetrics] keeps every reported metric that
 * has no column, by its source key, so a sample is never narrowed to the columns.
 */
data class SampleData(
    val sampleIndex: Int,
    val sampleTime: Instant? = null,
    val elapsedSeconds: Double? = null,
    val distance: Double? = null,
    val speed: Double? = null,
    val heartRate: Double? = null,
    val cadence: Double? = null,
    val power: Double? = null,
    val elevation: Double? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val temperature: Double? = null,
    val extraMetrics: ObjectNode? = null,
)

/**
 * How complete a stored sample stream is, relative to the source's own native point count.
 * Decided from the response, never from the size RunningAI asked for.
 */
enum class SampleCompleteness {
    /** Every point the source says the activity has was stored. */
    FULL,

    /** The source returned fewer points than the activity has; the stream is a down-sampled view. */
    DOWNSAMPLED,

    /** The source did not report a usable native count, or contradicted itself. Never treated as FULL. */
    UNKNOWN,
}

/**
 * Fidelity of one collected sample stream, provider-neutral. Only meaningful for
 * [DetailPayloadType.ACTIVITY_DETAILS_STREAM]; every other part records none.
 *
 * [requestedMaxChartSize] is what RunningAI asked for and is null when that is not known (a reprocess
 * of a payload fetched before it was recorded) — it is never reconstructed from the payload.
 */
data class SampleStreamFidelity(
    val completeness: SampleCompleteness,
    val requestedMaxChartSize: Int? = null,
    val sourceMetricsCount: Int? = null,
    val sourceTotalMetricsCount: Int? = null,
) {
    companion object {
        /**
         * Classifies a stored stream. [storedSampleCount] is the number of normalised rows,
         * [payloadSampleCount] the number of sample entries the payload actually carried.
         *
         * UNKNOWN whenever the source leaves the question open or contradicts itself: no usable
         * native count, a `metricsCount` that disagrees with the entries actually present, or more
         * stored rows than the source claims the activity has. The one case that yields FULL is an
         * internally consistent payload whose stored rows reach the native count.
         */
        fun of(
            storedSampleCount: Int,
            payloadSampleCount: Int,
            sourceMetricsCount: Int?,
            sourceTotalMetricsCount: Int?,
            requestedMaxChartSize: Int?,
        ): SampleStreamFidelity {
            val consistent = sourceMetricsCount == null || sourceMetricsCount == payloadSampleCount
            val total = sourceTotalMetricsCount?.takeIf { it >= 0 }
            val completeness = when {
                total == null || !consistent -> SampleCompleteness.UNKNOWN
                storedSampleCount == total -> SampleCompleteness.FULL
                storedSampleCount < total -> SampleCompleteness.DOWNSAMPLED
                else -> SampleCompleteness.UNKNOWN
            }
            return SampleStreamFidelity(completeness, requestedMaxChartSize, sourceMetricsCount, sourceTotalMetricsCount)
        }
    }
}

/** The recorded status of one part. [sampleFidelity] is null for every part but the sample stream. */
data class DetailPartRecord(
    val payloadType: DetailPayloadType,
    val status: DetailPartStatus,
    val errorCode: String?,
    val itemCount: Int?,
    val attemptedAt: Instant,
    val sampleFidelity: SampleStreamFidelity? = null,
)
