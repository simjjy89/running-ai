package com.runningai.integration.garmin;

/**
 * Per-metric verdict of one recovery collection (Phase 8A), mirroring {@link GarminRecoveryMapper}'s
 * own classification. Named {@code *Outcome} rather than {@code *Status} because
 * {@code garmin_recovery_daily.hrv_status} already holds GARMIN's label (e.g. {@code BALANCED}) and
 * the two must never be confused.
 */
public enum GarminRecoveryMetricOutcome {

    /** Garmin delivered at least one valid value for this metric. */
    AVAILABLE,

    /** Garmin itself reported no data for this metric (e.g. no watch worn overnight). */
    NO_DATA,

    /** Garmin's own call for this metric failed. */
    ERROR,

    /** An OK metric with no usable value, an unknown status, or no entry at all. */
    MALFORMED;

    /** The reason strings {@link GarminRecoveryMapper} puts in {@code unavailableMetrics}. */
    static GarminRecoveryMetricOutcome fromUnavailableReason(String reason) {
        return switch (reason) {
            case "NO_DATA" -> NO_DATA;
            case "ERROR" -> ERROR;
            default -> MALFORMED;
        };
    }
}
