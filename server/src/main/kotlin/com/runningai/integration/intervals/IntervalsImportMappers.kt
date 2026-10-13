package com.runningai.integration.intervals

import com.fasterxml.jackson.databind.JsonNode
import com.runningai.activity.ActivityType
import com.runningai.activity.ExternalSource
import com.runningai.activity.NormalizedActivity
import com.runningai.activity.detail.ActivityDetailData
import com.runningai.activity.detail.DetailPayloadType
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * One Intervals.icu activity as the importer sees it (Phase 7E-1).
 *
 * Deliberately separate from [com.runningai.enrichment.IntervalsActivitySnapshot], which is 6H-5's
 * enrichment contract and carries only the training-model numbers. Widening that type so an importer
 * could reuse it would put import-only fields into the enrichment path for no benefit.
 *
 * Every field is exactly what the payload said; nothing is defaulted and nothing is converted.
 */
data class IntervalsImportCandidate(
    val intervalsActivityId: String,
    /** `source`: GARMIN_CONNECT / OAUTH_CLIENT / STRAVA. Decides which path owns the activity. */
    val source: String?,
    /** `external_id`: the recording client's own id. For OAUTH_CLIENT a UUID - never a Garmin id. */
    val externalId: String?,
    /** `strava_id`: the same workout's Strava identity, kept for the Phase 7E-2 archive match. */
    val stravaActivityId: String?,
    val type: String?,
    val deviceName: String?,
    val startDate: Instant?,
    /** `start_date_local`, as text, so the stored record shows what was verified. */
    val startDateLocal: String?,
    val elapsedTimeSeconds: Int?,
    val movingTimeSeconds: Int?,
    val recordingTimeSeconds: Int?,
    val distanceMeters: Double?,
    val averageHeartRate: Int?,
    val maxHeartRate: Int?,
    val averageSpeed: Double?,
    val maxSpeed: Double?,
    val calories: Double?,
    val elevationGain: Double?,
    val elevationLoss: Double?,
    /** `icu_lap_count`. 0 on all 27 Intervals-only activities: the source has no lap structure. */
    val lapCount: Int?,
    /** `stream_types`: what the summary claims is recorded. The streams endpoint is the authority. */
    val declaredStreamTypes: List<String>,
    /** `analyzed`: when Intervals.icu last recomputed the activity. The importer's change signal. */
    val analyzedAt: Instant?,
    /**
     * True when the payload is the stub Intervals.icu returns instead of an activity - it carries
     * `_note` and little else. Live: all 34 STRAVA-sourced activities. Not an error, and not something
     * to work around: there is no data to import.
     */
    val noSourceData: Boolean,
) {
    /**
     * The duration RunningAI stores on the activity row.
     *
     * `icu_recording_time` is the seconds the device actually recorded - the analogue of Garmin's
     * `duration`, which is what every other activity row holds and what duration-based training load is
     * computed from. `elapsed_time` would include the time the athlete stood still (live: up to 297 s on
     * one run), which would overstate the load of exactly those sessions. Both other durations are kept
     * in `activity_detail`, so nothing is lost by choosing one here.
     */
    val durationSeconds: Int? get() = recordingTimeSeconds ?: movingTimeSeconds ?: elapsedTimeSeconds
}

/** Why an Intervals activity could not be turned into a RunningAI activity. The raw payload is kept. */
class IntervalsImportValidationException(val code: String, message: String) : RuntimeException(message)

/**
 * Pure mappers for the Intervals-only activity importer (Phase 7E-1). Intervals.icu field names end
 * here, exactly as in [IntervalsActivityMapper].
 *
 * Verified live on 2026-10-13 against all 27 Intervals-only activities of the real account: the field
 * names, their JSON types, `start_date` being an ISO instant with `Z`, `start_date_local` being a local
 * date-time without offset, `icu_lap_count` being 0 on every one of them, and the STRAVA stubs carrying
 * `_note` instead of data.
 */
@Component
class IntervalsImportCandidateMapper {

    fun map(item: JsonNode): IntervalsImportCandidate {
        if (!item.isObject) {
            throw IntervalsMappingException("INTERVALS_ACTIVITY_NOT_OBJECT", "Intervals activity payload is not a JSON object")
        }
        val id = text(item, "id")
            ?: throw IntervalsMappingException("INTERVALS_ACTIVITY_ID_MISSING", "Intervals activity has no id")
        return IntervalsImportCandidate(
            intervalsActivityId = id,
            source = text(item, "source"),
            externalId = text(item, "external_id"),
            stravaActivityId = text(item, "strava_id"),
            type = text(item, "type"),
            deviceName = text(item, "device_name"),
            startDate = instant(item, "start_date"),
            startDateLocal = text(item, "start_date_local"),
            elapsedTimeSeconds = int(item, "elapsed_time"),
            movingTimeSeconds = int(item, "moving_time"),
            recordingTimeSeconds = int(item, "icu_recording_time"),
            distanceMeters = double(item, "distance"),
            averageHeartRate = int(item, "average_heartrate"),
            maxHeartRate = int(item, "max_heartrate"),
            averageSpeed = double(item, "average_speed"),
            maxSpeed = double(item, "max_speed"),
            calories = double(item, "calories"),
            elevationGain = double(item, "total_elevation_gain"),
            elevationLoss = double(item, "total_elevation_loss"),
            lapCount = int(item, "icu_lap_count"),
            declaredStreamTypes = item.get("stream_types")?.takeIf { it.isArray }
                ?.mapNotNull { node -> node.takeIf { it.isTextual }?.asText() }.orEmpty(),
            analyzedAt = instant(item, "analyzed"),
            noSourceData = item.has("_note") && !item.has("type"),
        )
    }

    /**
     * The RunningAI activity row for [candidate], or a validation failure.
     *
     * Checked here, because an activity row that lies about when or how long a session was poisons every
     * derived number downstream:
     *
     *  - the start instant exists;
     *  - `start_date_local` really is `start_date` seen from the athlete's timezone (live: +09:00 on
     *    27/27). A mismatch means the activity was recorded somewhere else or the payload disagrees with
     *    itself, and either way RunningAI must not pick one of the two and continue;
     *  - the stored duration is positive;
     *  - a reported distance is not negative.
     *
     * The type map lists only the pairs the live Garmin-Intervals comparison already established
     * ([IntervalsActivityMatcher.COMPATIBLE_TYPES]); an unlisted type is unsupported rather than guessed.
     */
    fun toNormalizedActivity(candidate: IntervalsImportCandidate, athleteZone: ZoneId): NormalizedActivity {
        val startedAt = candidate.startDate
            ?: throw IntervalsImportValidationException("INTERVALS_START_DATE_MISSING",
                "Intervals activity ${candidate.intervalsActivityId} has no start_date")
        verifyLocalDate(candidate, startedAt, athleteZone)

        val activityType = activityTypeOf(candidate.type)
            ?: throw IntervalsImportValidationException("INTERVALS_UNSUPPORTED_TYPE",
                "No RunningAI activity type models Intervals type ${candidate.type}")

        val duration = candidate.durationSeconds
            ?: throw IntervalsImportValidationException("INTERVALS_DURATION_MISSING",
                "Intervals activity ${candidate.intervalsActivityId} reports no duration")
        if (duration <= 0) {
            throw IntervalsImportValidationException("INTERVALS_DURATION_INVALID", "Duration must be > 0")
        }
        val distance = candidate.distanceMeters
        if (distance != null && distance < 0) {
            throw IntervalsImportValidationException("INTERVALS_DISTANCE_INVALID", "Distance must be >= 0")
        }
        return NormalizedActivity(
            ExternalSource.INTERVALS_ICU,
            // The Intervals id verbatim, so the RunningAI row always names its true origin. No Garmin id
            // is invented for an activity Garmin never recorded.
            candidate.intervalsActivityId,
            activityType,
            startedAt,
            duration,
            distance,
            candidate.averageHeartRate,
            candidate.maxHeartRate,
        )
    }

    /** Activity-level detail from the same summary payload. No Intervals training-model number here. */
    fun toDetail(candidate: IntervalsImportCandidate) = ActivityDetailData(
        sourcePayloadType = DetailPayloadType.ACTIVITY_DETAIL,
        durationSeconds = candidate.recordingTimeSeconds?.toDouble(),
        elapsedDurationSeconds = candidate.elapsedTimeSeconds?.toDouble(),
        movingDurationSeconds = candidate.movingTimeSeconds?.toDouble(),
        distanceMeters = candidate.distanceMeters,
        averageSpeed = candidate.averageSpeed,
        maxSpeed = candidate.maxSpeed,
        averageHeartRateBpm = candidate.averageHeartRate?.toDouble(),
        maxHeartRateBpm = candidate.maxHeartRate?.toDouble(),
        // average_cadence is one-leg rpm at the source; this column's unit is steps per minute. Left
        // null rather than filled with a number in the wrong unit - see IntervalsStreamMapper.
        averageRunningCadenceSpm = null,
        maxRunningCadenceSpm = null,
        elevationGain = candidate.elevationGain,
        elevationLoss = candidate.elevationLoss,
        calories = candidate.calories,
        // icu_training_load / icu_intensity belong to activity_intervals_metrics (6H-5), where their
        // provenance is unambiguous. training_load here is Garmin's and stays null.
    )

    private fun verifyLocalDate(candidate: IntervalsImportCandidate, startedAt: Instant, zone: ZoneId) {
        val local = candidate.startDateLocal ?: return
        val parsed = try {
            LocalDateTime.parse(local)
        } catch (e: DateTimeParseException) {
            throw IntervalsImportValidationException("INTERVALS_LOCAL_DATE_UNPARSEABLE",
                "start_date_local of ${candidate.intervalsActivityId} is not an ISO local date-time")
        }
        if (parsed.atZone(zone).toInstant() != startedAt) {
            throw IntervalsImportValidationException("INTERVALS_TIMEZONE_MISMATCH",
                "start_date_local of ${candidate.intervalsActivityId} is not start_date in $zone")
        }
    }

    companion object {
        /**
         * Intervals.icu type label to RunningAI type. Only the pairs the 6H-5 live matching established;
         * `Ride` and `Workout` have no RunningAI type and are reported unsupported, never approximated.
         */
        val SUPPORTED_TYPES: Map<String, ActivityType> = mapOf(
            "Run" to ActivityType.RUN,
            "VirtualRun" to ActivityType.TREADMILL_RUN,
            "VirtualRide" to ActivityType.INDOOR_CYCLING,
        )

        fun activityTypeOf(intervalsType: String?): ActivityType? = SUPPORTED_TYPES[intervalsType]
    }
}

private fun text(node: JsonNode, field: String): String? {
    val v = node.get(field) ?: return null
    if (v.isNull) return null
    if (v.isNumber) return v.asText()
    if (!v.isTextual) throw IntervalsMappingException("INTERVALS_FIELD_NOT_TEXT", "Intervals field $field is not text")
    return v.asText()
}

private fun int(node: JsonNode, field: String): Int? {
    val v = node.get(field) ?: return null
    if (v.isNull) return null
    if (!v.isNumber) throw IntervalsMappingException("INTERVALS_FIELD_NOT_NUMBER", "Intervals field $field is not a number")
    return v.intValue()
}

private fun double(node: JsonNode, field: String): Double? {
    val v = node.get(field) ?: return null
    if (v.isNull) return null
    if (!v.isNumber) throw IntervalsMappingException("INTERVALS_FIELD_NOT_NUMBER", "Intervals field $field is not a number")
    return v.doubleValue()
}

private fun instant(node: JsonNode, field: String): Instant? {
    val raw = text(node, field) ?: return null
    return try {
        OffsetDateTime.parse(raw).toInstant()
    } catch (e: DateTimeParseException) {
        throw IntervalsMappingException("INTERVALS_FIELD_NOT_TIMESTAMP", "Intervals field $field is not an ISO timestamp")
    }
}
