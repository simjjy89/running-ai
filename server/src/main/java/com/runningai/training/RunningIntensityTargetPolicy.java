package com.runningai.training;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.Map;

/**
 * Converts one running segment's {@link IntensityClass} plus the athlete's LTHR /
 * threshold pace into numeric targets. Pure: no persistence, no HTTP, no clock.
 * <p>
 * These multipliers/percentages are RunningAI's initial deterministic scheduling
 * heuristics, not universal physiological thresholds, and are not tuned from real
 * athlete data. Only {@link IntensityClass#VERY_EASY} and {@link IntensityClass#EASY}
 * have a defined band today (5B-1 never produces a running segment with any other
 * class outside QUALITY, which is rejected upstream); any other class falls back to
 * no numeric pace/HR target rather than guessing one.
 */
final class RunningIntensityTargetPolicy {

    private record PaceBand(double fastMultiplier, double slowMultiplier) {
    }

    private record HeartRateBand(int minPercent, int maxPercent) {
    }

    // Percent of threshold pace (seconds/km); smaller multiplier = faster pace.
    private static final Map<IntensityClass, PaceBand> PACE_BANDS = new EnumMap<>(Map.of(
            IntensityClass.VERY_EASY, new PaceBand(1.25, 1.45),
            IntensityClass.EASY, new PaceBand(1.15, 1.30)));

    // Percent of LTHR.
    private static final Map<IntensityClass, HeartRateBand> HEART_RATE_BANDS = new EnumMap<>(Map.of(
            IntensityClass.VERY_EASY, new HeartRateBand(65, 78),
            IntensityClass.EASY, new HeartRateBand(75, 85)));

    private RunningIntensityTargetPolicy() {
    }

    /** {@code null} when the intensity class has no defined band or no threshold pace is set. */
    static PaceTarget paceTarget(IntensityClass intensityClass, Integer thresholdPaceSecondsPerKm) {
        PaceBand band = PACE_BANDS.get(intensityClass);
        if (band == null || thresholdPaceSecondsPerKm == null) {
            return null;
        }
        int fast = (int) Math.round(thresholdPaceSecondsPerKm * band.fastMultiplier());
        int slow = (int) Math.round(thresholdPaceSecondsPerKm * band.slowMultiplier());
        return new PaceTarget(fast, slow);
    }

    /** {@code null} when the intensity class has no defined band or no LTHR is set. */
    static HeartRateTarget heartRateTarget(IntensityClass intensityClass, Integer lactateThresholdHeartRateBpm) {
        HeartRateBand band = HEART_RATE_BANDS.get(intensityClass);
        if (band == null || lactateThresholdHeartRateBpm == null) {
            return null;
        }
        int minBpm = (int) Math.round(lactateThresholdHeartRateBpm * band.minPercent() / 100.0);
        int maxBpm = (int) Math.round(lactateThresholdHeartRateBpm * band.maxPercent() / 100.0);
        return new HeartRateTarget(band.minPercent(), band.maxPercent(), minBpm, maxBpm);
    }

    /**
     * Always populated for a running segment (incline is an operational default independent of the
     * athlete's profile); speed is {@code null} when {@code paceTarget} is {@code null}.
     */
    static TreadmillTarget treadmillTarget(SegmentType segmentType, PaceTarget paceTarget) {
        double minIncline;
        double maxIncline;
        switch (segmentType) {
            case WARM_UP, COOL_DOWN -> {
                minIncline = 0.0;
                maxIncline = 0.5;
            }
            case MAIN -> {
                minIncline = 0.5;
                maxIncline = 1.0;
            }
            default -> throw new IllegalArgumentException("No treadmill defaults for segment type " + segmentType);
        }
        Double minSpeedKph = null;
        Double maxSpeedKph = null;
        if (paceTarget != null) {
            // Pace and speed run in opposite directions: the slowest pace gives the lowest speed.
            minSpeedKph = speedFromPace(paceTarget.slowSecondsPerKm());
            maxSpeedKph = speedFromPace(paceTarget.fastSecondsPerKm());
        }
        return new TreadmillTarget(minSpeedKph, maxSpeedKph, minIncline, maxIncline);
    }

    private static double speedFromPace(int paceSecondsPerKm) {
        double kph = 3600.0 / paceSecondsPerKm;
        return BigDecimal.valueOf(kph).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
