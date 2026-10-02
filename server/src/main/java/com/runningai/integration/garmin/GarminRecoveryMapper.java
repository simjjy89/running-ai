package com.runningai.integration.garmin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.runningai.integration.garmin.GarminConnectorException.Reason;
import com.runningai.recovery.RecoveryDailyValues;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Pure normalisation of the connector's {@code GET /recovery} body (Phase 6F contract, see
 * {@code tools/garmin-connector/garmin_connector/recovery.py}):
 * <pre>
 * {"date": "YYYY-MM-DD",
 *  "metrics": {"hrv":              {"status": "OK", "data": {"lastNightAvg", "weeklyAvg", "status"}},
 *              "sleep":            {"status": ..., "data": {"sleepTimeSeconds", "sleepScore"}},
 *              "restingHeartRate": {"status": ..., "data": {"value"}},
 *              "bodyBattery":      {"status": ..., "data": {"highest", "lowest", "charged", "drained"}},
 *              "stress":           {"status": ..., "data": {"avgStressLevel", "maxStressLevel"}}}}
 * </pre>
 * Values keep Garmin's units. A missing, non-numeric, non-finite or negative value becomes
 * {@code null} (never a default); a body for a different day than requested is rejected outright so
 * it can never be stored under the wrong date. No unit conversion, rating or baseline happens here.
 */
final class GarminRecoveryMapper {

    static final List<String> METRICS = List.of("hrv", "sleep", "restingHeartRate", "bodyBattery", "stress");

    private static final Pattern HRV_STATUS = Pattern.compile("[A-Za-z0-9_]{1,32}");

    private GarminRecoveryMapper() {
    }

    static GarminRecoveryDay map(JsonNode raw, LocalDate requested) {
        if (raw == null || !raw.isObject() || !requested.toString().equals(raw.path("date").asText(null))) {
            throw new GarminConnectorException(Reason.INVALID_RESPONSE, 200,
                    "Garmin connector returned recovery metrics for a different or missing date");
        }
        JsonNode metrics = raw.path("metrics");

        JsonNode hrv = okData(metrics, "hrv");
        JsonNode sleep = okData(metrics, "sleep");
        JsonNode rhr = okData(metrics, "restingHeartRate");
        JsonNode battery = okData(metrics, "bodyBattery");
        JsonNode stress = okData(metrics, "stress");

        String hrvStatus = hrv.path("status").isTextual() && HRV_STATUS.matcher(hrv.path("status").asText()).matches()
                ? hrv.path("status").asText().toUpperCase()
                : null;
        Integer restingHeartRate = nonNegativeInt(rhr.path("value"));
        if (restingHeartRate != null && restingHeartRate == 0) {
            restingHeartRate = null;
        }

        RecoveryDailyValues values = new RecoveryDailyValues(
                nonNegativeDouble(hrv.path("lastNightAvg")),
                nonNegativeDouble(hrv.path("weeklyAvg")),
                hrvStatus,
                nonNegativeInt(sleep.path("sleepTimeSeconds")),
                nonNegativeInt(sleep.path("sleepScore")),
                restingHeartRate,
                nonNegativeInt(battery.path("highest")),
                nonNegativeInt(battery.path("lowest")),
                nonNegativeInt(battery.path("charged")),
                nonNegativeInt(battery.path("drained")),
                nonNegativeInt(stress.path("avgStressLevel")),
                nonNegativeInt(stress.path("maxStressLevel")));

        Map<String, Boolean> delivered = Map.of(
                "hrv", values.hrvLastNightAvgMs() != null || values.hrvWeeklyAvgMs() != null || hrvStatus != null,
                "sleep", values.sleepDurationSeconds() != null || values.sleepScore() != null,
                "restingHeartRate", restingHeartRate != null,
                "bodyBattery", values.bodyBatteryHighest() != null || values.bodyBatteryLowest() != null
                        || values.bodyBatteryCharged() != null || values.bodyBatteryDrained() != null,
                "stress", values.stressAverage() != null || values.stressMax() != null);

        List<String> available = new ArrayList<>();
        Map<String, String> unavailable = new LinkedHashMap<>();
        for (String name : METRICS) {
            if (delivered.get(name)) {
                available.add(name);
            } else {
                unavailable.put(name, unavailableReason(metrics.path(name)));
            }
        }
        return new GarminRecoveryDay(requested, values, List.copyOf(available), unavailable);
    }

    /** The {@code data} object of a metric reported OK, otherwise a missing node (all reads yield null). */
    private static JsonNode okData(JsonNode metrics, String name) {
        JsonNode metric = metrics.path(name);
        JsonNode data = metric.path("data");
        return "OK".equals(metric.path("status").asText(null)) && data.isObject() ? data : MissingNode.getInstance();
    }

    private static String unavailableReason(JsonNode metric) {
        String status = metric.path("status").asText(null);
        if ("NO_DATA".equals(status) || "ERROR".equals(status)) {
            return status;
        }
        // OK without a single valid value, an unknown status, or no entry at all
        return "MALFORMED";
    }

    private static Double nonNegativeDouble(JsonNode node) {
        if (node == null || !node.isNumber()) {
            return null;
        }
        double value = node.asDouble();
        return Double.isFinite(value) && value >= 0 ? value : null;
    }

    private static Integer nonNegativeInt(JsonNode node) {
        Double value = nonNegativeDouble(node);
        if (value == null || value > Integer.MAX_VALUE) {
            return null;
        }
        return (int) Math.round(value);
    }
}
