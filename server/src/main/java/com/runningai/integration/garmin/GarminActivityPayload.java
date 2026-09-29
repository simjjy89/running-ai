package com.runningai.integration.garmin;

import java.time.Instant;

/**
 * The subset of a Garmin activity payload that RunningAI needs to build a
 * normalised activity. Everything else stays in the raw JSONB snapshot.
 * <p>
 * Units are already normalised here: {@code startTime} is a UTC instant,
 * {@code durationSeconds} whole seconds, {@code distanceMeters} metres.
 */
public record GarminActivityPayload(
        String activityId,
        String activityName,
        String activityType,
        Instant startTime,
        long durationSeconds,
        Double distanceMeters,
        Integer averageHeartRate,
        Integer maxHeartRate
) {
}
