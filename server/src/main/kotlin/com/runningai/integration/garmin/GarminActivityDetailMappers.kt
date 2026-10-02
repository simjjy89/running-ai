package com.runningai.integration.garmin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.runningai.activity.detail.ActivityDetailData
import com.runningai.activity.detail.DetailPayloadType
import com.runningai.activity.detail.LapData
import com.runningai.activity.detail.SampleData
import com.runningai.activity.detail.ZoneData
import com.runningai.activity.detail.ZoneType
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * A detail payload could not be normalised. The raw payload is already stored; the code is safe to
 * persist and log (no payload content, no identifier beyond what the caller logs itself).
 */
class GarminDetailMappingException(val code: String, message: String) : RuntimeException(message)

// Garmin field names end in this file: everything returned from here is provider-neutral.
//
// Provenance of the keys (see docs/architecture/garmin-detailed-activity-contract-static.md):
//  - activity-list keys: STATIC_SOURCE_CONFIRMED by python-garminconnect 0.3.16 typed.Activity aliases,
//    then CONFIRMED_LIVE (Phase 3B-3 and, for every key read below, Phase 6H-1B);
//  - lap, zone and sample keys: CONFIRMED_LIVE in Main-PC Phase 6H-1B against four real activities
//    (outdoor run, track interval run, treadmill run, indoor cycling). No key below needed correcting.
//
// Two live facts the mapping depends on:
//  - a metric's index is meaningful only inside its own payload (the four live activities produced four
//    different layouts), so descriptors are resolved per payload and never by position;
//  - the descriptors' `unit.factor` is not a conversion divisor - live values already carry the stated
//    unit - so values are stored exactly as reported.
//
// A key that is nonetheless wrong loses nothing: the raw payload is stored first and unknown keys go to
// extraMetrics, so a corrected mapper is applied with `reprocess` without contacting Garmin again.

private fun JsonNode.numberOrNull(key: String, where: String): Double? {
    val v = get(key) ?: return null
    if (v.isNull) return null
    if (!v.isNumber) throw GarminDetailMappingException("NON_NUMERIC_METRIC", "$where: $key is not numeric")
    val d = v.asDouble()
    if (!d.isFinite()) throw GarminDetailMappingException("NON_FINITE_METRIC", "$where: $key is not finite")
    return d
}

private fun JsonNode.textOrNull(key: String): String? = get(key)?.takeUnless { it.isNull }?.asText()

private fun JsonNode.isEmptyPayload() = isNull || (isContainerNode && isEmpty)

/** Activity-level metrics from one activity-list item (the payload in `activity_raw`). */
@Component
class GarminActivityDetailMapper {

    fun map(listItem: JsonNode): ActivityDetailData {
        if (!listItem.isObject) throw GarminDetailMappingException("NOT_AN_OBJECT", "activity-list item is not an object")
        val w = "activity"
        return ActivityDetailData(
            sourcePayloadType = DetailPayloadType.ACTIVITY_LIST,
            durationSeconds = listItem.numberOrNull("duration", w),
            elapsedDurationSeconds = listItem.numberOrNull("elapsedDuration", w),
            movingDurationSeconds = listItem.numberOrNull("movingDuration", w),
            distanceMeters = listItem.numberOrNull("distance", w),
            averageSpeed = listItem.numberOrNull("averageSpeed", w),
            maxSpeed = listItem.numberOrNull("maxSpeed", w),
            averageHeartRateBpm = listItem.numberOrNull("averageHR", w),
            maxHeartRateBpm = listItem.numberOrNull("maxHR", w),
            averageRunningCadenceSpm = listItem.numberOrNull("averageRunningCadenceInStepsPerMinute", w),
            maxRunningCadenceSpm = listItem.numberOrNull("maxRunningCadenceInStepsPerMinute", w),
            elevationGain = listItem.numberOrNull("elevationGain", w),
            elevationLoss = listItem.numberOrNull("elevationLoss", w),
            calories = listItem.numberOrNull("calories", w),
            averagePower = listItem.numberOrNull("avgPower", w),
            maxPower = listItem.numberOrNull("maxPower", w),
            normalizedPower = listItem.numberOrNull("normPower", w),
            aerobicTrainingEffect = listItem.numberOrNull("aerobicTrainingEffect", w),
            anaerobicTrainingEffect = listItem.numberOrNull("anaerobicTrainingEffect", w),
            trainingLoad = listItem.numberOrNull("activityTrainingLoad", w),
            trainingEffectLabel = listItem.textOrNull("trainingEffectLabel"),
        )
    }
}

/** Laps from the splits payload. CONFIRMED_LIVE shape: `{"activityId", "lapDTOs": [...], "eventDTOs": [...]}`. */
@Component
class GarminLapMapper(private val objectMapper: ObjectMapper) {

    fun map(payload: JsonNode): List<LapData> {
        if (payload.isEmptyPayload()) return emptyList()
        val laps = payload.get(LAPS)
        if (!payload.isObject || laps == null || !laps.isArray) {
            throw GarminDetailMappingException("UNKNOWN_SPLITS_SHAPE", "splits payload has no $LAPS array")
        }
        val result = laps.mapIndexed { position, lap -> mapLap(position, lap) }
        val duplicate = result.groupBy { it.lapIndex }.filterValues { it.size > 1 }.keys.firstOrNull()
        if (duplicate != null) throw GarminDetailMappingException("DUPLICATE_LAP_INDEX", "lap index $duplicate repeats")
        return result
    }

    private fun mapLap(position: Int, lap: JsonNode): LapData {
        if (!lap.isObject) throw GarminDetailMappingException("NOT_AN_OBJECT", "lap $position is not an object")
        val w = "lap $position"
        // The source's own index when present (live Garmin numbers laps from 1; the 0-based messageIndex
        // stays an extra metric); otherwise the lap's position in the source's ordered list (structural,
        // not a metric).
        val index = lap.get("lapIndex")?.takeUnless { it.isNull }?.let {
            if (!it.canConvertToExactIntegral() || !it.canConvertToInt()) {
                throw GarminDetailMappingException("INVALID_LAP_INDEX", "$w: lapIndex is not an integer")
            }
            it.intValue()
        } ?: position
        if (index < 0) throw GarminDetailMappingException("INVALID_LAP_INDEX", "$w: lapIndex is negative")
        return LapData(
            lapIndex = index,
            startTime = lap.textOrNull("startTimeGMT")?.let { parseGmt(it, w) },
            durationSeconds = lap.numberOrNull("duration", w),
            elapsedDurationSeconds = lap.numberOrNull("elapsedDuration", w),
            movingDurationSeconds = lap.numberOrNull("movingDuration", w),
            distanceMeters = lap.numberOrNull("distance", w),
            averageSpeed = lap.numberOrNull("averageSpeed", w),
            maxSpeed = lap.numberOrNull("maxSpeed", w),
            averageHeartRateBpm = lap.numberOrNull("averageHR", w),
            maxHeartRateBpm = lap.numberOrNull("maxHR", w),
            averageCadence = lap.numberOrNull("averageRunCadence", w),
            maxCadence = lap.numberOrNull("maxRunCadence", w),
            averagePower = lap.numberOrNull("averagePower", w),
            maxPower = lap.numberOrNull("maxPower", w),
            elevationGain = lap.numberOrNull("elevationGain", w),
            elevationLoss = lap.numberOrNull("elevationLoss", w),
            calories = lap.numberOrNull("calories", w),
            extraMetrics = extras(lap),
        )
    }

    private fun extras(lap: JsonNode): ObjectNode? {
        val extra = objectMapper.createObjectNode()
        lap.properties().forEach { (key, value) -> if (key !in LAP_KEYS && !value.isNull) extra.set<JsonNode>(key, value) }
        return extra.takeUnless { it.isEmpty }
    }

    private companion object {
        const val LAPS = "lapDTOs"
        val LAP_KEYS = setOf(
            "lapIndex", "startTimeGMT", "duration", "elapsedDuration", "movingDuration", "distance",
            "averageSpeed", "maxSpeed", "averageHR", "maxHR", "averageRunCadence", "maxRunCadence",
            "averagePower", "maxPower", "elevationGain", "elevationLoss", "calories",
        )
    }
}

/** Time in zones. CONFIRMED_LIVE shape: `[{"zoneNumber", "secsInZone", "zoneLowBoundary"}, ...]`, no upper bound. */
@Component
class GarminZoneMapper {

    fun map(payload: JsonNode, type: ZoneType): List<ZoneData> {
        if (payload.isEmptyPayload()) return emptyList()
        if (!payload.isArray) throw GarminDetailMappingException("UNKNOWN_ZONES_SHAPE", "$type zones payload is not an array")
        val zones = payload.mapIndexed { position, zone ->
            val w = "$type zone $position"
            if (!zone.isObject) throw GarminDetailMappingException("NOT_AN_OBJECT", "$w is not an object")
            val number = zone.get("zoneNumber")
            if (number == null || !number.canConvertToExactIntegral() || !number.canConvertToInt()) {
                throw GarminDetailMappingException("INVALID_ZONE_NUMBER", "$w has no integer zoneNumber")
            }
            ZoneData(
                zoneType = type,
                zoneNumber = number.intValue(),
                minValue = zone.numberOrNull("zoneLowBoundary", w),
                maxValue = null,   // never derived from the next zone's lower boundary
                durationSeconds = zone.numberOrNull("secsInZone", w),
            )
        }
        val duplicate = zones.groupBy { it.zoneNumber }.filterValues { it.size > 1 }.keys.firstOrNull()
        if (duplicate != null) throw GarminDetailMappingException("DUPLICATE_ZONE", "$type zone $duplicate repeats")
        return zones
    }
}

/**
 * Samples from the activity-details stream, resolved **by descriptor, per payload**.
 *
 * CONFIRMED_LIVE envelope (Phase 6H-1B; statically from garminconnect/activity_details.py): every sample's
 * `metrics` is a positional array whose meaning is given by that response's `metricDescriptors[].metricsIndex/key`.
 * Four live activities from one device produced four different layouts - `directTimestamp` sat at index 7, 5,
 * 9 and 2 - so the index of a metric is looked up in the descriptors of the very payload being mapped; no
 * position is ever assumed. Live timestamps arrive as JSON floats holding whole epoch milliseconds. Descriptor rules follow the
 * library: a non-string key or a missing/non-integer/negative index is skipped; an index beyond a
 * sample's array means "not reported" for that sample. Two descriptors claiming one index, or one key at
 * two indexes, is ambiguous and fails the mapping (raw kept).
 *
 * Samples keep the source's sampling: one row per `activityDetailMetrics` entry, nothing interpolated.
 */
@Component
class GarminSampleMapper(private val objectMapper: ObjectMapper) {

    fun map(payload: JsonNode): List<SampleData> {
        if (payload.isEmptyPayload()) return emptyList()
        if (!payload.isObject) throw GarminDetailMappingException("UNKNOWN_SAMPLES_SHAPE", "samples payload is not an object")
        val rows = payload.get("activityDetailMetrics")
        if (rows == null || rows.isNull || (rows.isArray && rows.isEmpty)) return emptyList()
        if (!rows.isArray) throw GarminDetailMappingException("UNKNOWN_SAMPLES_SHAPE", "activityDetailMetrics is not an array")
        val keyByIndex = descriptors(payload.get("metricDescriptors"))
        return rows.mapIndexed { position, row -> mapSample(position, row, keyByIndex) }
    }

    /** index -> key, from this payload only. */
    private fun descriptors(node: JsonNode?): Map<Int, String> {
        if (node == null || !node.isArray) {
            throw GarminDetailMappingException("MISSING_METRIC_DESCRIPTORS", "samples have no metricDescriptors array")
        }
        val keyByIndex = linkedMapOf<Int, String>()
        node.forEach { d ->
            val key = d.get("key")
            val index = d.get("metricsIndex")
            if (key == null || !key.isTextual) return@forEach
            if (index == null || !index.isIntegralNumber || !index.canConvertToInt() || index.intValue() < 0) return@forEach
            val i = index.intValue()
            val previous = keyByIndex.putIfAbsent(i, key.textValue())
            if (previous != null && previous != key.textValue()) {
                throw GarminDetailMappingException("AMBIGUOUS_METRIC_DESCRIPTOR", "metricsIndex $i is claimed by two keys")
            }
        }
        if (keyByIndex.values.toSet().size != keyByIndex.size) {
            throw GarminDetailMappingException("AMBIGUOUS_METRIC_DESCRIPTOR", "a metric key is described at two indexes")
        }
        return keyByIndex
    }

    private fun mapSample(position: Int, row: JsonNode, keyByIndex: Map<Int, String>): SampleData {
        val w = "sample $position"
        val metrics = row.get("metrics")
        if (metrics == null || !metrics.isArray) throw GarminDetailMappingException("UNKNOWN_SAMPLES_SHAPE", "$w has no metrics array")
        val values = linkedMapOf<String, JsonNode>()
        keyByIndex.forEach { (index, key) ->
            if (index < metrics.size()) {
                val v = metrics.get(index)
                if (v != null && !v.isNull) values[key] = v
            }
        }
        fun num(key: String): Double? {
            val v = values[key] ?: return null
            if (!v.isNumber) throw GarminDetailMappingException("NON_NUMERIC_METRIC", "$w: $key is not numeric")
            return v.asDouble().also {
                if (!it.isFinite()) throw GarminDetailMappingException("NON_FINITE_METRIC", "$w: $key is not finite")
            }
        }
        val timestamp = values[TIMESTAMP]?.let {
            if (!it.canConvertToExactIntegral() || !it.canConvertToLong()) {
                throw GarminDetailMappingException("INVALID_TIMESTAMP", "$w: $TIMESTAMP is not epoch milliseconds")
            }
            Instant.ofEpochMilli(it.longValue())
        }
        val extra = objectMapper.createObjectNode()
        values.forEach { (key, value) -> if (key !in COLUMN_KEYS) extra.set<JsonNode>(key, value) }
        return SampleData(
            sampleIndex = position,
            sampleTime = timestamp,
            elapsedSeconds = num("sumElapsedDuration"),
            distance = num("sumDistance"),
            speed = num("directSpeed"),
            heartRate = num("directHeartRate"),
            cadence = num("directRunCadence"),
            power = num("directPower"),
            elevation = num("directElevation"),
            latitude = num("directLatitude"),
            longitude = num("directLongitude"),
            temperature = num("directAirTemperature"),
            extraMetrics = extra.takeUnless { it.isEmpty },
        )
    }

    private companion object {
        const val TIMESTAMP = "directTimestamp"
        val COLUMN_KEYS = setOf(
            TIMESTAMP, "sumElapsedDuration", "sumDistance", "directSpeed", "directHeartRate", "directRunCadence",
            "directPower", "directElevation", "directLatitude", "directLongitude", "directAirTemperature",
        )
    }
}

private val GMT_SPACE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/**
 * A Garmin `*GMT` timestamp: `yyyy-MM-dd HH:mm:ss` (the activity-list form, CONFIRMED_LIVE), ISO local
 * date-time without a zone, or ISO with an offset. A value without a zone is UTC (the field is GMT).
 */
internal fun parseGmt(text: String, where: String): Instant = try {
    when {
        text.endsWith("Z") || Regex("[+-]\\d{2}:?\\d{2}$").containsMatchIn(text.substringAfter('T', "")) ->
            java.time.OffsetDateTime.parse(text).toInstant()
        text.contains('T') -> LocalDateTime.parse(text).toInstant(ZoneOffset.UTC)
        else -> LocalDateTime.parse(text, GMT_SPACE).toInstant(ZoneOffset.UTC)
    }
} catch (e: DateTimeParseException) {
    throw GarminDetailMappingException("INVALID_TIMESTAMP", "$where: unreadable GMT timestamp")
}
