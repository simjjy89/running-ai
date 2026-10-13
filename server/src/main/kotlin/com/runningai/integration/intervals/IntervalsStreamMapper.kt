package com.runningai.integration.intervals

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.runningai.activity.detail.SampleData
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Result of mapping one activity's Intervals.icu streams: the samples plus what the mapping could and
 * could not represent. [qualityFlags] and [missingTypes] are facts about the DATA, never a judgement
 * about the session, and they are stored so a later reader never has to guess why a column is null.
 */
data class IntervalsStreamMappingResult(
    val samples: List<SampleData>,
    /** Stream types the payload carried, in payload order. */
    val availableTypes: List<String>,
    /** Types that reached a normalised sample column or `extra_metrics`. */
    val importedTypes: List<String>,
    /** Types RunningAI knows about that this activity did not record. */
    val missingTypes: List<String>,
    /** Types the payload carried that this mapper has no rule for; kept in the raw payload only. */
    val unknownTypes: List<String>,
    /** Facts that constrain how the stored samples may be read. See [IntervalsStreamMapper]. */
    val qualityFlags: List<String>,
    /** Elapsed seconds between the first and last sample, from the `time` stream. */
    val spanSeconds: Int?,
)

/**
 * Pure mapper from the Intervals.icu `GET /api/v1/activity/{id}/streams` payload to [SampleData]
 * (Phase 7E-1). Intervals.icu field names end here.
 *
 * **Live contract, measured 2026-10-13 on the real account over all 27 Intervals-only activities**
 * (nothing below is inferred from documentation):
 *
 *  - the body is an array of stream objects `{type, name, data, data2, valueType, valueTypeIsArray,
 *    anomalies, custom, allNull}`;
 *  - within one activity every stream's `data` array has the SAME length (checked, 27/27), so position
 *    *i* is the same instant in every stream;
 *  - `time` carries ELAPSED SECONDS, not an index: its last value equals the activity's `elapsed_time`
 *    (27/27) while the array is shorter whenever the recording had gaps. A sample's instant is
 *    therefore `start_date + time[i]`, never `start_date + i`;
 *  - `icu_median_time_delta` is 1 on all 27 activities - the streams are native 1 Hz;
 *  - `latlng` puts LATITUDE in `data` and LONGITUDE in `data2`;
 *  - 13 stream types occur: time, heartrate, cadence, distance, velocity_smooth, watts, altitude,
 *    latlng, torque, fixed_altitude, stance_time, vertical_oscillation, step_length. Coverage varies
 *    per activity (heartrate 27/27, step_length 17/27), which is why [IntervalsStreamMappingResult]
 *    reports what was present rather than assuming a fixed set;
 *  - a type can be present and entirely null (`watts` on a run without a power meter). Null stays null.
 *
 * **Units, and the one conflict.** distance (m), velocity_smooth (m/s), heartrate (bpm), watts (W) and
 * altitude (m) are the same quantities in the same units as the Garmin sample columns, so they map to
 * those columns directly. `cadence` does NOT: Intervals reports one-leg REVOLUTIONS per minute, while
 * Garmin's `directRunCadence` - the only thing that has ever been in the `cadence` column - is STEPS
 * per minute. The factor is exactly 2, verified arithmetically on the live data (distance / duration =
 * 2 x cadence / 60 x `average_stride`, to 4 significant figures), but verifying a factor is not
 * permission to apply one: `activity_sample` keeps values exactly as the source reported them, and
 * putting rpm in a column every other row reads as spm would make a wrong number indistinguishable from
 * a right one. So the cadence stream is kept under its own source key in `extra_metrics`, the `cadence`
 * column stays null, and [CADENCE_SOURCE_RPM] records why. The consequence is explicit: cadence metrics
 * are unavailable for Intervals-imported activities until a unit-normalisation phase is decided on.
 *
 * Running-dynamics streams (stance_time ms, vertical_oscillation mm, step_length mm) have no sample
 * column at all and likewise stay in `extra_metrics` under their source keys, unconverted.
 */
@Component
class IntervalsStreamMapper {

    fun map(body: JsonNode, startedAt: Instant): IntervalsStreamMappingResult {
        if (!body.isArray) {
            throw IntervalsMappingException("INTERVALS_STREAMS_NOT_ARRAY", "Intervals streams payload is not a JSON array")
        }
        val streams = body.mapNotNull { node -> streamOf(node) }
        if (streams.isEmpty()) {
            return IntervalsStreamMappingResult(
                emptyList(), emptyList(), emptyList(), KNOWN_TYPES.toList(), emptyList(),
                listOf(NO_STREAMS), null,
            )
        }

        val lengths = streams.map { it.data.size() }.toSet()
        if (lengths.size > 1) {
            // Position i would no longer mean the same instant in every stream. Nothing is truncated to
            // the shortest: that would silently drop measurements, and the raw payload is already stored.
            throw IntervalsMappingException("INTERVALS_STREAMS_LENGTH_MISMATCH",
                "Intervals streams of one activity have different lengths: $lengths")
        }
        val length = lengths.first()
        val byType = streams.associateBy { it.type }
        val time = byType[TIME]

        val quality = mutableListOf<String>()
        if (time == null) quality += NO_TIME_STREAM
        if (CADENCE in byType) quality += CADENCE_SOURCE_RPM
        if (byType.values.any { it.allNull }) {
            quality += ALL_NULL_STREAMS + "=" + byType.values.filter { it.allNull }.map { it.type }.sorted().joinToString("+")
        }

        val samples = (0 until length).map { i -> sampleAt(i, byType, time, startedAt) }
        val available = streams.map { it.type }
        val known = available.filter { it in KNOWN_TYPES }
        return IntervalsStreamMappingResult(
            samples = samples,
            availableTypes = available,
            // `time` is the time base, not a measurement; it is reported as available but never counted
            // as an imported metric.
            importedTypes = known.filter { it != TIME },
            missingTypes = KNOWN_TYPES.filter { it != TIME && it !in byType },
            unknownTypes = available.filter { it !in KNOWN_TYPES },
            qualityFlags = quality,
            spanSeconds = time?.let { elapsedAt(it, length - 1)?.toInt() },
        )
    }

    private fun sampleAt(index: Int, byType: Map<String, Stream>, time: Stream?, startedAt: Instant): SampleData {
        val elapsed = time?.let { elapsedAt(it, index) }
        val extras: ObjectNode = JsonNodeFactory.instance.objectNode()
        EXTRA_TYPES.forEach { type ->
            val value = byType[type]?.data?.get(index)
            if (value != null && !value.isNull) extras.set<JsonNode>(type, value)
        }
        return SampleData(
            sampleIndex = index,
            // Elapsed seconds from the `time` stream, never the row position: the stream skips the
            // seconds the device did not record.
            sampleTime = elapsed?.let { startedAt.plusSeconds(it) },
            elapsedSeconds = elapsed?.toDouble(),
            distance = numberAt(byType[DISTANCE], index),
            speed = numberAt(byType[VELOCITY], index),
            heartRate = numberAt(byType[HEART_RATE], index),
            // Deliberately null: the source unit is rpm, the column's unit is spm. See the class doc.
            cadence = null,
            power = numberAt(byType[WATTS], index),
            elevation = numberAt(byType[ALTITUDE], index),
            latitude = numberAt(byType[LATLNG], index),
            longitude = byType[LATLNG]?.data2?.get(index)?.takeIf { it.isNumber }?.doubleValue(),
            temperature = null,
            extraMetrics = if (extras.isEmpty) null else extras,
        )
    }

    private fun elapsedAt(time: Stream, index: Int): Long? =
        time.data.get(index)?.takeIf { it.isNumber }?.longValue()

    private fun numberAt(stream: Stream?, index: Int): Double? =
        stream?.data?.get(index)?.takeIf { it.isNumber }?.doubleValue()

    private fun streamOf(node: JsonNode): Stream? {
        if (!node.isObject) {
            throw IntervalsMappingException("INTERVALS_STREAM_NOT_OBJECT", "An Intervals stream entry is not a JSON object")
        }
        val type = node.get("type")?.takeIf { it.isTextual }?.asText() ?: return null
        val data = node.get("data")
        if (data == null || !data.isArray) {
            throw IntervalsMappingException("INTERVALS_STREAM_DATA_NOT_ARRAY", "Intervals stream $type has no data array")
        }
        val data2 = node.get("data2")?.takeIf { it.isArray }
        val allNull = node.get("allNull")?.takeIf { it.isBoolean }?.asBoolean() ?: false
        return Stream(type, data, data2, allNull)
    }

    private class Stream(val type: String, val data: JsonNode, val data2: JsonNode?, val allNull: Boolean)

    companion object {
        const val TIME = "time"
        const val HEART_RATE = "heartrate"
        const val CADENCE = "cadence"
        const val DISTANCE = "distance"
        const val VELOCITY = "velocity_smooth"
        const val WATTS = "watts"
        const val ALTITUDE = "altitude"
        const val LATLNG = "latlng"
        const val TORQUE = "torque"
        const val FIXED_ALTITUDE = "fixed_altitude"
        const val STANCE_TIME = "stance_time"
        const val VERTICAL_OSCILLATION = "vertical_oscillation"
        const val STEP_LENGTH = "step_length"

        /** The 13 types observed live. An unlisted type is reported as unknown, never silently dropped. */
        val KNOWN_TYPES = listOf(
            TIME, HEART_RATE, CADENCE, DISTANCE, VELOCITY, WATTS, ALTITUDE, LATLNG,
            TORQUE, FIXED_ALTITUDE, STANCE_TIME, VERTICAL_OSCILLATION, STEP_LENGTH,
        )

        /** Types kept in `extra_metrics` under their source key: no column, or a conflicting unit. */
        val EXTRA_TYPES = listOf(
            CADENCE, TORQUE, FIXED_ALTITUDE, STANCE_TIME, VERTICAL_OSCILLATION, STEP_LENGTH,
        )

        /** Cadence is one-leg rpm at the source and was NOT converted to the column's spm. */
        const val CADENCE_SOURCE_RPM = "CADENCE_SOURCE_RPM_NOT_NORMALIZED"

        /** The payload carried no stream at all. */
        const val NO_STREAMS = "NO_STREAMS"

        /** No `time` stream: sample instants and elapsed seconds could not be established. */
        const val NO_TIME_STREAM = "NO_TIME_STREAM"

        /** Prefix of the flag listing streams the source recorded as entirely empty. */
        const val ALL_NULL_STREAMS = "ALL_NULL_STREAMS"
    }
}
