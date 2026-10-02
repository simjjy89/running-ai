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

/** One lap / split. [extraMetrics] keeps every source field that has no column, by its source key. */
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

/** The recorded status of one part. */
data class DetailPartRecord(
    val payloadType: DetailPayloadType,
    val status: DetailPartStatus,
    val errorCode: String?,
    val itemCount: Int?,
    val attemptedAt: Instant,
)
