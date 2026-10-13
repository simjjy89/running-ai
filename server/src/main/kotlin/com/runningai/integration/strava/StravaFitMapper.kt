package com.runningai.integration.strava

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.runningai.activity.ActivityType
import com.runningai.activity.ExternalSource
import com.runningai.activity.NormalizedActivity
import com.runningai.activity.detail.ActivityDetailData
import com.runningai.activity.detail.DetailPayloadType
import com.runningai.activity.detail.LapData
import com.runningai.activity.detail.SampleData
import org.springframework.stereotype.Component
import java.time.Instant
import kotlin.math.roundToInt

/** A decoded FIT activity cannot become a RunningAI activity. [code] is stable and machine-readable. */
class StravaMappingException(val code: String, message: String) : RuntimeException(message)

/**
 * Turns a decoded [FitActivity] into the repository's normalised shapes (Phase 7E-2).
 *
 * Pure: no I/O, no clock, no database. Every rule it applies is a stated rule, not a heuristic:
 *
 *  - **Sport decides the type, the file's own label does not.** Strava's `Activity Type` column says
 *    "Ride" for sessions the FIT itself records as `CYCLING`/`INDOOR_CYCLING` with no GPS and no
 *    distance. The FIT sport/sub_sport is the device's statement and is what is mapped; an unmapped
 *    sport is refused rather than approximated, so a strength session never becomes a run.
 *  - **Cadence is not converted.** FIT running cadence is one-leg rpm; `activity_sample.cadence` is
 *    Garmin's steps per minute (factor 2). Rather than silently store one unit as the other - the trap
 *    Phase 7E-1 documented for Intervals - the column stays null, the source value is kept in
 *    `extra_metrics` under its own key, and [CADENCE_SOURCE_RPM_NOT_NORMALIZED] is flagged.
 *  - **Nothing is estimated.** A channel the file does not carry stays null, including heart rate.
 */
@Component
class StravaFitMapper(private val objectMapper: ObjectMapper) {

    fun activityTypeOf(fit: FitActivity): ActivityType? = when (fit.sport?.uppercase()) {
        "RUNNING" -> if (fit.subSport?.uppercase() == "TREADMILL") {
            ActivityType.TREADMILL_RUN
        } else {
            ActivityType.RUN
        }
        // Only an explicitly INDOOR_CYCLING ride maps. An outdoor ride has no ActivityType, and
        // guessing one from "it recorded no GPS" would be inference, not reading.
        "CYCLING" -> if (fit.subSport?.uppercase() == "INDOOR_CYCLING") ActivityType.INDOOR_CYCLING else null
        else -> null
    }

    fun toNormalizedActivity(entry: StravaArchiveEntry, fit: FitActivity): NormalizedActivity {
        val type = activityTypeOf(fit)
            ?: throw StravaMappingException(
                "NO_ACTIVITY_TYPE_FOR_${fit.sport}_${fit.subSport}",
                "no ActivityType models FIT sport=${fit.sport} subSport=${fit.subSport}")
        val startedAt = fit.startedAt
            ?: throw StravaMappingException("FIT_NO_START_TIME", "FIT session has no start time")
        // The ELAPSED time is the activity's duration, matching every other ingestion in the repo.
        val duration = fit.elapsedTimeSeconds
            ?: throw StravaMappingException("FIT_NO_ELAPSED_TIME", "FIT session has no elapsed time")
        return NormalizedActivity(
            ExternalSource.STRAVA,
            entry.stravaActivityId,
            type,
            startedAt,
            duration.roundToInt(),
            fit.distanceMeters?.takeIf { it > 0.0 },
            fit.averageHeartRateBpm?.roundToInt(),
            fit.maxHeartRateBpm?.roundToInt(),
        )
    }

    fun toDetail(fit: FitActivity) = ActivityDetailData(
        sourcePayloadType = DetailPayloadType.ACTIVITY_DETAIL,
        durationSeconds = fit.timerTimeSeconds,
        elapsedDurationSeconds = fit.elapsedTimeSeconds,
        movingDurationSeconds = fit.timerTimeSeconds,
        distanceMeters = fit.distanceMeters?.takeIf { it > 0.0 },
        averageSpeed = fit.averageSpeed,
        maxSpeed = fit.maxSpeed,
        averageHeartRateBpm = fit.averageHeartRateBpm,
        maxHeartRateBpm = fit.maxHeartRateBpm,
        // Deliberately NOT filled from the FIT's one-leg rpm: the column is steps per minute.
        averageRunningCadenceSpm = null,
        maxRunningCadenceSpm = null,
        elevationGain = fit.elevationGain,
        elevationLoss = fit.elevationLoss,
        calories = fit.calories,
        averagePower = fit.averagePower,
        maxPower = fit.maxPower,
        normalizedPower = fit.normalizedPower,
    )

    /**
     * Laps exactly as recorded.
     *
     * [LapData.intensityType] and [LapData.workoutStepIndex] are passed through when the file states
     * them and left null when it does not. A Strava re-encode of an Apple Health workout states
     * neither, so the interval-structure extractor will correctly find no groups for it: the athlete
     * pressed the lap button, the watch never recorded a programmed workout, and manufacturing an
     * ACTIVE/RECOVERY pattern from the pace of each lap is exactly the inference the analysis package
     * forbids.
     */
    fun toLaps(fit: FitActivity): List<LapData> = fit.laps.map { lap ->
        val derivedSpeed = lap.derivedAverageSpeed()
        LapData(
            lapIndex = lap.lapIndex,
            startTime = lap.startTime,
            durationSeconds = lap.timerTimeSeconds,
            elapsedDurationSeconds = lap.elapsedTimeSeconds,
            movingDurationSeconds = lap.timerTimeSeconds,
            distanceMeters = lap.distanceMeters,
            averageSpeed = lap.averageSpeed ?: derivedSpeed,
            maxSpeed = lap.maxSpeed,
            averageHeartRateBpm = lap.averageHeartRateBpm,
            maxHeartRateBpm = lap.maxHeartRateBpm,
            averageCadence = null,
            maxCadence = null,
            averagePower = lap.averagePower,
            maxPower = lap.maxPower,
            calories = lap.calories,
            intensityType = lap.intensity,
            workoutIndex = null,
            workoutStepIndex = lap.workoutStepIndex,
            extraMetrics = extras(
                "lap_trigger" to lap.lapTrigger,
                "average_cadence_rpm" to lap.averageCadenceRpm,
                // Provenance, required by the repository's rule that a derived value never passes for
                // a measured one: present only when the number below came from the arithmetic.
                "average_speed_source" to
                    if (lap.averageSpeed == null && derivedSpeed != null) DERIVED_SPEED_SOURCE else null,
            ),
        )
    }

    /**
     * A lap's average speed from the lap's OWN recorded total distance and timer time.
     *
     * A Strava re-encode states `total_distance` and `total_timer_time` per lap but no `avg_speed`,
     * so without this the lap-level metrics of a restored session would all be null and the laps would
     * carry only boundaries. Average speed over a lap is distance divided by time by definition - this
     * is the identity, not a model or an estimate, and it is computed only from two values the same
     * lap message states. It is nonetheless marked as derived in `extra_metrics`, because a reader
     * must always be able to tell a measured value from a computed one.
     */
    private fun FitLap.derivedAverageSpeed(): Double? {
        val distance = distanceMeters ?: return null
        val seconds = timerTimeSeconds ?: elapsedTimeSeconds ?: return null
        if (distance <= 0.0 || seconds <= 0.0) return null
        return distance / seconds
    }

    /**
     * Samples in file order.
     *
     * [SampleData.elapsedSeconds] is measured from the session start, so a recording gap stays a gap
     * instead of being closed by re-indexing - the same rule the Intervals stream mapper follows.
     */
    fun toSamples(fit: FitActivity, startedAt: Instant): List<SampleData> =
        fit.records.mapIndexed { index, record ->
            SampleData(
                sampleIndex = index,
                sampleTime = record.timestamp,
                elapsedSeconds = record.timestamp?.let { (it.toEpochMilli() - startedAt.toEpochMilli()) / 1000.0 },
                distance = record.distance,
                speed = record.speed,
                heartRate = record.heartRateBpm,
                cadence = null,
                power = record.power,
                elevation = record.elevation,
                latitude = record.latitude,
                longitude = record.longitude,
                temperature = record.temperature,
                extraMetrics = extras(
                    "cadence_rpm" to record.cadenceRpm,
                    "stance_time" to record.stanceTime,
                    "vertical_oscillation" to record.verticalOscillation,
                    "step_length" to record.stepLength,
                ),
            )
        }

    /** Quality statements about what this file could and could not give RunningAI. */
    fun qualityFlags(fit: FitActivity): List<String> = buildList {
        if (fit.records.any { it.cadenceRpm != null }) add(CADENCE_SOURCE_RPM_NOT_NORMALIZED)
        if (fit.records.none { it.heartRateBpm != null }) add(NO_HEART_RATE_IN_SOURCE)
        if (fit.laps.isEmpty()) add(NO_LAPS_IN_SOURCE)
        if (fit.laps.isNotEmpty() && fit.laps.none { it.intensity != null }) {
            add(LAP_INTENSITY_NOT_RECORDED_BY_SOURCE)
        }
        if (fit.laps.isNotEmpty() && fit.laps.none { it.workoutStepIndex != null }) {
            add(LAP_WORKOUT_STEP_NOT_RECORDED_BY_SOURCE)
        }
        if (fit.laps.isNotEmpty() && fit.laps.none { it.averageSpeed != null }) {
            add(LAP_SPEED_DERIVED_FROM_DISTANCE_AND_TIME)
        }
        if (fit.laps.isNotEmpty() && fit.laps.none { it.averageHeartRateBpm != null }) {
            add(LAP_HEART_RATE_NOT_RECORDED_BY_SOURCE)
        }
        if (fit.records.isEmpty()) add(NO_RECORDS_IN_SOURCE)
    }

    private fun extras(vararg pairs: Pair<String, Any?>): ObjectNode? {
        val present = pairs.filter { it.second != null }
        if (present.isEmpty()) return null
        val node = objectMapper.createObjectNode()
        present.forEach { (key, value) ->
            when (value) {
                is Double -> node.put(key, value)
                is Int -> node.put(key, value)
                else -> node.put(key, value.toString())
            }
        }
        return node
    }

    companion object {
        /** FIT cadence is one-leg rpm; the cadence column is steps per minute. Never silently equated. */
        const val CADENCE_SOURCE_RPM_NOT_NORMALIZED: String = "CADENCE_SOURCE_RPM_NOT_NORMALIZED"
        const val NO_HEART_RATE_IN_SOURCE: String = "NO_HEART_RATE_IN_SOURCE"
        const val NO_LAPS_IN_SOURCE: String = "NO_LAPS_IN_SOURCE"
        const val NO_RECORDS_IN_SOURCE: String = "NO_RECORDS_IN_SOURCE"

        /** Laps exist but carry no intensity, so interval GROUPS cannot be derived - only lap metrics. */
        const val LAP_INTENSITY_NOT_RECORDED_BY_SOURCE: String = "LAP_INTENSITY_NOT_RECORDED_BY_SOURCE"
        const val LAP_WORKOUT_STEP_NOT_RECORDED_BY_SOURCE: String = "LAP_WORKOUT_STEP_NOT_RECORDED_BY_SOURCE"

        /** The lap's average speed is RunningAI's distance/time, not a figure the source stated. */
        const val LAP_SPEED_DERIVED_FROM_DISTANCE_AND_TIME: String = "LAP_SPEED_DERIVED_FROM_DISTANCE_AND_TIME"
        const val DERIVED_SPEED_SOURCE: String = "DERIVED_DISTANCE_OVER_TIME"

        /** No per-lap heart rate exists in the source, and none is derived from another source's stream. */
        const val LAP_HEART_RATE_NOT_RECORDED_BY_SOURCE: String = "LAP_HEART_RATE_NOT_RECORDED_BY_SOURCE"
    }
}
