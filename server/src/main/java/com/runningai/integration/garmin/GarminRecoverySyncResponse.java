package com.runningai.integration.garmin;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Result of syncing one day of Garmin recovery metrics. Never carries metric values or any raw
 * Garmin payload - only which metrics arrived.
 *
 * @param updated            true when the stored day was created or changed
 * @param availableMetrics   metrics Garmin delivered for the day ({@code hrv, sleep, restingHeartRate,
 *                           bodyBattery, stress})
 * @param unavailableMetrics the others, with {@code NO_DATA}, {@code MALFORMED} or {@code ERROR}
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record GarminRecoverySyncResponse(
        LocalDate date,
        boolean updated,
        List<String> availableMetrics,
        Map<String, String> unavailableMetrics
) {
}
