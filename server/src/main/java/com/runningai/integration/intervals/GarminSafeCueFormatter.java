package com.runningai.integration.intervals;

import com.runningai.training.TreadmillTarget;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * Turns {@link TreadmillTarget}'s numeric km/h and percent values into the short,
 * ASCII-safe Garmin step-cue text the legacy PowerShell renderer used
 * ({@code Get-GarminSafeStepCue}, confirmed by Forerunner 265 observation that cue text
 * only reaches the watch when placed before a step's duration/target token -- see
 * {@link IntervalsWorkoutRenderer}). Takes numeric values only, never a pre-rendered
 * free-text note to re-parse.
 * <p>
 * Speed keeps a fixed one decimal place (legacy examples always render {@code 12.0kph},
 * never {@code 12kph}); incline trims trailing zeros ({@code 1.0} -> {@code 1},
 * {@code 0.5} stays {@code 0.5}), matching the legacy {@code "Incline0.5-1pct"} example.
 * Legacy's own cue text never carried a genuine speed *range* (its treadmill notes were
 * a single fixed value); the dash-range form here for a non-degenerate {@code min != max}
 * is this port's own consistent extension of the already-established incline-range
 * convention, not a separately confirmed legacy fixture.
 */
final class GarminSafeCueFormatter {

    private GarminSafeCueFormatter() {
    }

    /** {@code null} when there is no treadmill numeric information to cue at all. */
    static String cue(TreadmillTarget treadmill) {
        if (treadmill == null) {
            return null;
        }
        String speed = speedToken(treadmill.minSpeedKph(), treadmill.maxSpeedKph());
        String incline = inclineToken(treadmill.minInclinePercent(), treadmill.maxInclinePercent());
        return speed == null ? incline : speed + " " + incline;
    }

    /** {@code null} when no pace target (and therefore no speed) is available. */
    private static String speedToken(Double minKph, Double maxKph) {
        if (minKph == null || maxKph == null) {
            return null;
        }
        validateFiniteNonNegative(minKph, "minSpeedKph");
        validateFiniteNonNegative(maxKph, "maxSpeedKph");
        String rendered = minKph.doubleValue() == maxKph.doubleValue()
                ? formatSpeed(maxKph)
                : formatSpeed(minKph) + "-" + formatSpeed(maxKph);
        return rendered + "kph";
    }

    private static String inclineToken(double minPercent, double maxPercent) {
        validateFiniteNonNegative(minPercent, "minInclinePercent");
        validateFiniteNonNegative(maxPercent, "maxInclinePercent");
        String rendered = minPercent == maxPercent
                ? formatIncline(maxPercent)
                : formatIncline(minPercent) + "-" + formatIncline(maxPercent);
        return "Incline" + rendered + "pct";
    }

    private static String formatSpeed(double kph) {
        return String.format(Locale.ROOT, "%.1f", kph);
    }

    /** Trims trailing zeros: {@code 1.0 -> "1"}, {@code 0.5 -> "0.5"}, {@code 0.0 -> "0"}. */
    private static String formatIncline(double percent) {
        BigDecimal trimmed = BigDecimal.valueOf(percent).stripTrailingZeros();
        if (trimmed.scale() < 0) {
            trimmed = trimmed.setScale(0);
        }
        return trimmed.toPlainString();
    }

    private static void validateFiniteNonNegative(double value, String field) {
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException("Treadmill " + field + " must be a finite, non-negative number: " + value);
        }
    }
}
