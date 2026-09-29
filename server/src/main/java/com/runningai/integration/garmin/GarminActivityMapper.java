package com.runningai.integration.garmin;

import com.fasterxml.jackson.databind.JsonNode;
import com.runningai.activity.ActivityType;
import com.runningai.activity.ExternalSource;
import com.runningai.activity.NormalizedActivity;
import com.runningai.integration.garmin.GarminActivityMappingException.Reason;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;

/**
 * Pure transformation from a Garmin activity JSON payload to RunningAI's
 * {@link NormalizedActivity}. No database access.
 * <p>
 * Payload contract (fixture-based until Phase 3B confirms the live shape):
 * <pre>
 *   activityId      number|string   required, becomes Activity.externalId
 *   activityName    string          optional
 *   activityType    {typeKey} | string   required; see {@link #ACTIVITY_TYPES}
 *   startTime       ISO-8601 with offset (preferred)
 *   startTimeGMT    "yyyy-MM-dd HH:mm:ss" or ISO, interpreted as UTC (fallback)
 *   startTimeLocal  "yyyy-MM-dd HH:mm:ss" + timeZoneId (fallback)
 *   duration        MILLISECONDS, required  -> durationSeconds (rounded)
 *   distance        metres, optional
 *   averageHR       bpm, optional
 *   maxHR           bpm, optional
 * </pre>
 */
@Component
public class GarminActivityMapper {

    /** Garmin type keys (lower-cased) that RunningAI understands. */
    static final Map<String, ActivityType> ACTIVITY_TYPES = Map.ofEntries(
            Map.entry("running", ActivityType.RUN),
            Map.entry("run", ActivityType.RUN),
            Map.entry("trail_running", ActivityType.RUN),
            Map.entry("track_running", ActivityType.RUN),
            Map.entry("treadmill_running", ActivityType.TREADMILL_RUN),
            Map.entry("treadmill", ActivityType.TREADMILL_RUN),
            Map.entry("indoor_running", ActivityType.TREADMILL_RUN),
            Map.entry("indoor_cycling", ActivityType.INDOOR_CYCLING),
            Map.entry("indoor_biking", ActivityType.INDOOR_CYCLING),
            Map.entry("cycling_indoor", ActivityType.INDOOR_CYCLING),
            Map.entry("virtual_ride", ActivityType.INDOOR_CYCLING)
    );

    private static final DateTimeFormatter GARMIN_LOCAL_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Extracts the Garmin activity id, the only field needed before the raw
     * payload can be stored.
     */
    public String extractActivityId(JsonNode payload) {
        JsonNode node = payload == null ? null : payload.get("activityId");
        if (node == null || node.isNull() || node.asText().isBlank()) {
            throw new GarminActivityMappingException(Reason.ACTIVITY_ID_MISSING, null, "activityId is missing");
        }
        return node.asText().trim();
    }

    public NormalizedActivity map(JsonNode payload) {
        return toNormalized(parse(payload));
    }

    /** Reads and unit-normalises the fields RunningAI needs from the raw JSON. */
    public GarminActivityPayload parse(JsonNode payload) {
        String activityId = extractActivityId(payload);
        String typeKey = readTypeKey(payload, activityId);
        Instant startTime = readStartTime(payload, activityId);
        long durationSeconds = readDurationSeconds(payload, activityId);
        Double distance = readDouble(payload, "distance", activityId);
        Integer averageHr = readInteger(payload, "averageHR", activityId);
        Integer maxHr = readInteger(payload, "maxHR", activityId);
        String name = payload.hasNonNull("activityName") ? payload.get("activityName").asText() : null;

        return new GarminActivityPayload(activityId, name, typeKey, startTime, durationSeconds, distance, averageHr, maxHr);
    }

    public NormalizedActivity toNormalized(GarminActivityPayload payload) {
        ActivityType activityType = mapActivityType(payload.activityType(), payload.activityId());
        if (payload.durationSeconds() > Integer.MAX_VALUE) {
            throw new GarminActivityMappingException(Reason.INVALID_VALUE, payload.activityId(),
                    "duration out of range: " + payload.durationSeconds());
        }
        return new NormalizedActivity(
                ExternalSource.GARMIN,
                payload.activityId(),
                activityType,
                payload.startTime(),
                (int) payload.durationSeconds(),
                payload.distanceMeters(),
                payload.averageHeartRate(),
                payload.maxHeartRate()
        );
    }

    ActivityType mapActivityType(String typeKey, String activityId) {
        if (typeKey == null || typeKey.isBlank()) {
            throw new GarminActivityMappingException(Reason.ACTIVITY_TYPE_MISSING, activityId, "activityType is missing");
        }
        ActivityType type = ACTIVITY_TYPES.get(typeKey.trim().toLowerCase(Locale.ROOT));
        if (type == null) {
            throw new GarminActivityMappingException(Reason.UNSUPPORTED_ACTIVITY_TYPE, activityId,
                    "unsupported Garmin activity type '" + typeKey + "'");
        }
        return type;
    }

    private String readTypeKey(JsonNode payload, String activityId) {
        JsonNode typeNode = payload.get("activityType");
        if (typeNode == null || typeNode.isNull()) {
            throw new GarminActivityMappingException(Reason.ACTIVITY_TYPE_MISSING, activityId, "activityType is missing");
        }
        if (typeNode.isObject()) {
            JsonNode key = typeNode.get("typeKey");
            return key == null || key.isNull() ? null : key.asText();
        }
        return typeNode.asText();
    }

    private Instant readStartTime(JsonNode payload, String activityId) {
        try {
            if (payload.hasNonNull("startTime")) {
                return OffsetDateTime.parse(payload.get("startTime").asText()).toInstant();
            }
            if (payload.hasNonNull("startTimeGMT")) {
                return parseUtc(payload.get("startTimeGMT").asText());
            }
            if (payload.hasNonNull("startTimeLocal") && payload.hasNonNull("timeZoneId")) {
                LocalDateTime local = parseLocal(payload.get("startTimeLocal").asText());
                return local.atZone(ZoneId.of(payload.get("timeZoneId").asText())).toInstant();
            }
        } catch (DateTimeException e) {
            throw new GarminActivityMappingException(Reason.START_TIME_INVALID, activityId,
                    "start time could not be parsed: " + e.getMessage());
        }
        throw new GarminActivityMappingException(Reason.START_TIME_MISSING, activityId,
                "no startTime, startTimeGMT or startTimeLocal+timeZoneId");
    }

    private static Instant parseUtc(String text) {
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException ignored) {
            return parseLocal(text).toInstant(ZoneOffset.UTC);
        }
    }

    private static LocalDateTime parseLocal(String text) {
        try {
            return LocalDateTime.parse(text, GARMIN_LOCAL_FORMAT);
        } catch (DateTimeParseException ignored) {
            return LocalDateTime.parse(text);
        }
    }

    /** Garmin fixture duration is in milliseconds; RunningAI keeps whole seconds. */
    private long readDurationSeconds(JsonNode payload, String activityId) {
        JsonNode node = payload.get("duration");
        if (node == null || node.isNull() || !node.isNumber()) {
            throw new GarminActivityMappingException(Reason.DURATION_MISSING, activityId, "duration (ms) is missing");
        }
        double millis = node.asDouble();
        if (millis < 0) {
            throw new GarminActivityMappingException(Reason.INVALID_VALUE, activityId, "duration must be >= 0");
        }
        return Math.round(millis / 1000.0);
    }

    private Double readDouble(JsonNode payload, String field, String activityId) {
        JsonNode node = payload.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isNumber()) {
            throw new GarminActivityMappingException(Reason.INVALID_VALUE, activityId, field + " is not numeric");
        }
        double value = node.asDouble();
        if (value < 0) {
            throw new GarminActivityMappingException(Reason.INVALID_VALUE, activityId, field + " must be >= 0");
        }
        return value;
    }

    private Integer readInteger(JsonNode payload, String field, String activityId) {
        JsonNode node = payload.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isNumber()) {
            throw new GarminActivityMappingException(Reason.INVALID_VALUE, activityId, field + " is not numeric");
        }
        int value = (int) Math.round(node.asDouble());
        if (value < 0) {
            throw new GarminActivityMappingException(Reason.INVALID_VALUE, activityId, field + " must be >= 0");
        }
        return value;
    }
}
