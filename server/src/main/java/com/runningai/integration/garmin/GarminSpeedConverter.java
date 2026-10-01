package com.runningai.integration.garmin;

/**
 * Converts Garmin's {@code latestLactateThreshold} raw {@code speed} value into a running pace
 * (seconds per kilometre).
 * <p>
 * The unit of this field is <b>not documented</b> by Garmin or by {@code python-garminconnect}
 * 0.3.16 (its source carries no unit comment, and no public issue/PR states one). It was derived
 * empirically in Phase 6D-0 against a live account: the raw value {@code 0.34444348}, read as
 * plain m/s, gives an implausible 48:23/km running pace; multiplied by 10 it gives
 * {@code 3.4444348 m/s} = {@code 290.3 sec/km} (4:50.3/km), matching the athlete's independently
 * known real lactate-threshold pace (290 sec/km, manually entered from the Garmin Connect app)
 * to within 0.1%. Conclusion: the raw value is in units of 0.1 m/s.
 * <p>
 * See {@code docs/work-orders/2026-10-02-phase-6d-garmin-athlete-profile-auto-sync.md} for the
 * full derivation. If Garmin's actual unit is ever confirmed differently, only this class needs
 * to change.
 */
final class GarminSpeedConverter {

    /** Raw Garmin speed unit -> metres/second. */
    private static final double RAW_SPEED_UNITS_PER_METER_PER_SECOND = 10.0;

    private static final double METERS_PER_KILOMETER = 1000.0;

    private GarminSpeedConverter() {
    }

    /**
     * @param rawSpeed raw {@code speed_and_heart_rate.speed} value, or {@code null} if absent
     * @return seconds per kilometre, or {@code null} if {@code rawSpeed} is absent, non-finite,
     * not strictly positive, or converts to a non-finite/overflowing/non-positive result
     */
    static Integer rawSpeedToSecondsPerKilometer(Double rawSpeed) {
        if (rawSpeed == null || !Double.isFinite(rawSpeed) || rawSpeed <= 0) {
            return null;
        }
        double metersPerSecond = rawSpeed * RAW_SPEED_UNITS_PER_METER_PER_SECOND;
        double secondsPerKm = METERS_PER_KILOMETER / metersPerSecond;
        if (!Double.isFinite(secondsPerKm) || secondsPerKm <= 0 || secondsPerKm > Integer.MAX_VALUE) {
            return null;
        }
        return (int) Math.round(secondsPerKm);
    }
}
