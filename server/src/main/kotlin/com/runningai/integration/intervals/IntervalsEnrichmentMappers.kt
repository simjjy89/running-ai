package com.runningai.integration.intervals

import com.fasterxml.jackson.databind.JsonNode
import com.runningai.enrichment.IntervalsActivitySnapshot
import com.runningai.enrichment.IntervalsWellnessDay
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * An Intervals.icu payload did not have the live-verified shape. The raw payload is already stored when
 * this is thrown, so nothing is lost; the message carries field names only, never values.
 */
class IntervalsMappingException(val code: String, message: String) : RuntimeException(message)

/**
 * Pure mappers from live-verified Intervals.icu payloads (2026-10-03, real account) to the enrichment
 * models. Intervals.icu field names end here. Rules:
 *
 *  - A missing optional field is null, never a default.
 *  - A present field of the wrong JSON type is contract drift and fails the mapping (raw preserved).
 *  - Unknown additional fields are ignored here and survive in the stored raw payload.
 *
 * Verified field forms: `start_date` is an ISO instant with `Z` (`2026-09-18T02:10:45Z`);
 * `analyzed` / `updated` carry an offset (`...+00:00`); `elapsed_time` / `moving_time` are integer
 * seconds; `distance` is metres; `icu_training_load` is an integer; `icu_intensity`, `icu_ctl`,
 * `icu_atl`, wellness `ctl` / `atl` / `ctlLoad` / `atlLoad` / `rampRate` are floats.
 */
@Component
class IntervalsActivityMapper {

    /** Maps one completed-activity item (the list item and the single-activity GET share this shape). */
    fun map(item: JsonNode): IntervalsActivitySnapshot {
        if (!item.isObject) {
            throw IntervalsMappingException("INTERVALS_ACTIVITY_NOT_OBJECT", "Intervals activity payload is not a JSON object")
        }
        val id = text(item, "id")
            ?: throw IntervalsMappingException("INTERVALS_ACTIVITY_ID_MISSING", "Intervals activity has no id")
        return IntervalsActivitySnapshot(
            intervalsActivityId = id,
            source = text(item, "source"),
            externalId = text(item, "external_id"),
            type = text(item, "type"),
            startDate = instant(item, "start_date"),
            elapsedTimeSeconds = int(item, "elapsed_time"),
            movingTimeSeconds = int(item, "moving_time"),
            distanceMeters = double(item, "distance"),
            trainingLoad = int(item, "icu_training_load"),
            intensity = double(item, "icu_intensity"),
            ctlAfterActivity = double(item, "icu_ctl"),
            atlAfterActivity = double(item, "icu_atl"),
            sourceUpdatedAt = instant(item, "analyzed"),
        )
    }
}

@Component
class IntervalsWellnessMapper {

    /**
     * Maps one wellness entry's fitness-model numbers. The subjective `fatigue` field is deliberately
     * never read: ATL is the calculated fatigue and comes from `atl` only. Recovery-flavoured fields
     * (`hrv`, `sleepSecs`, `restingHR`, ...) are also not read — Garmin Recovery is the recovery source
     * of truth; they survive in the stored raw payload.
     */
    fun map(entry: JsonNode): IntervalsWellnessDay {
        if (!entry.isObject) {
            throw IntervalsMappingException("INTERVALS_WELLNESS_NOT_OBJECT", "Intervals wellness payload is not a JSON object")
        }
        val id = text(entry, "id")
            ?: throw IntervalsMappingException("INTERVALS_WELLNESS_ID_MISSING", "Intervals wellness entry has no id")
        val date = try {
            LocalDate.parse(id)
        } catch (e: DateTimeParseException) {
            throw IntervalsMappingException("INTERVALS_WELLNESS_ID_NOT_DATE", "Intervals wellness id is not an ISO date")
        }
        return IntervalsWellnessDay(
            date = date,
            ctl = double(entry, "ctl"),
            atl = double(entry, "atl"),
            ctlLoad = double(entry, "ctlLoad"),
            atlLoad = double(entry, "atlLoad"),
            rampRate = double(entry, "rampRate"),
            sourceUpdatedAt = instant(entry, "updated"),
        )
    }
}

private fun text(node: JsonNode, field: String): String? {
    val v = node.get(field) ?: return null
    if (v.isNull) return null
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

/** Accepts both live timestamp forms: `...Z` (start_date) and `...+00:00` (analyzed, updated). */
private fun instant(node: JsonNode, field: String): Instant? {
    val raw = text(node, field) ?: return null
    return try {
        OffsetDateTime.parse(raw).toInstant()
    } catch (e: DateTimeParseException) {
        throw IntervalsMappingException("INTERVALS_FIELD_NOT_TIMESTAMP", "Intervals field $field is not an ISO timestamp")
    }
}
