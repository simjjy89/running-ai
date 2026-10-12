package com.runningai.integration.garmin;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Pure normalisation of the connector's raw {@code GET /lactate-threshold} body (Phase 6D-0
 * contract: a {@code speed_and_heart_rate} object with {@code heartRate} (bpm) and {@code speed}
 * (raw Garmin units, see {@link GarminSpeedConverter}); a sibling {@code power} object is ignored -
 * cycling/running functional threshold power is out of scope for this phase).
 * <p>
 * A missing or malformed field becomes {@code null} in the result rather than throwing: the
 * connector may legitimately report one metric and not the other (or neither), and the caller
 * decides what a {@code null} means (see {@link GarminLactateThresholdSnapshot}). Nothing here
 * ever talks to the network or the database.
 */
final class GarminLactateThresholdMapper {

    private GarminLactateThresholdMapper() {
    }

    static GarminLactateThresholdSnapshot map(JsonNode raw) {
        if (raw == null || raw.isMissingNode() || raw.isNull()) {
            return GarminLactateThresholdSnapshot.EMPTY;
        }
        JsonNode speedAndHeartRate = raw.path("speed_and_heart_rate");
        Integer bpm = toPositiveBpm(speedAndHeartRate.path("heartRate"));
        Double rawSpeed = toFiniteDouble(speedAndHeartRate.path("speed"));
        Integer paceSecondsPerKm = GarminSpeedConverter.rawSpeedToSecondsPerKilometer(rawSpeed);
        // Phase 7B-4: the measurement timestamp is carried VERBATIM (live shape
        // "2026-10-06T00:50:55.648", no offset, zone undocumented) - parsing it into an Instant
        // would be a guess, so provenance stores the text and only RunningAI's own fetch time is
        // a real UTC instant. Non-text/absent -> null, truncated defensively to the column width.
        String calendarDateRaw = null;
        JsonNode calendarDate = speedAndHeartRate.path("calendarDate");
        if (calendarDate.isTextual() && !calendarDate.asText().isBlank()) {
            String text = calendarDate.asText();
            calendarDateRaw = text.length() <= 40 ? text : text.substring(0, 40);
        }
        return new GarminLactateThresholdSnapshot(bpm, paceSecondsPerKm, calendarDateRaw);
    }

    private static Integer toPositiveBpm(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isNumber()) {
            return null;
        }
        double value = node.asDouble();
        if (!Double.isFinite(value) || value <= 0 || value > Integer.MAX_VALUE) {
            return null;
        }
        return (int) Math.round(value);
    }

    private static Double toFiniteDouble(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isNumber()) {
            return null;
        }
        double value = node.asDouble();
        return Double.isFinite(value) ? value : null;
    }
}
