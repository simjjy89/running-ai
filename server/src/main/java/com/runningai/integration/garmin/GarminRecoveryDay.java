package com.runningai.integration.garmin;

import com.runningai.recovery.RecoveryDailyValues;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * One day of Garmin recovery metrics after normalisation.
 *
 * @param availableMetrics   connector metric names that delivered at least one valid value,
 *                           in the connector's order ({@code hrv, sleep, restingHeartRate, bodyBattery, stress})
 * @param unavailableMetrics every other metric with the reason: {@code NO_DATA}, {@code MALFORMED} or
 *                           {@code ERROR} (a failed Garmin call for that metric only)
 */
public record GarminRecoveryDay(
        LocalDate date,
        RecoveryDailyValues values,
        List<String> availableMetrics,
        Map<String, String> unavailableMetrics
) {
}
