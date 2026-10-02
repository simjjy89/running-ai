package com.runningai.recovery;

import java.time.LocalDate;

/**
 * One metric's most recent value compared with the athlete's own recent history. Pure measurement:
 * nothing here says whether a difference is good or bad.
 *
 * @param date              the day the current value was recorded (on or before the target date)
 * @param ageDays           target date minus {@code date}; 0 means recorded for the target day itself
 * @param current           the value on {@code date}
 * @param baseline          mean of the valid values in the {@code windowDays} days before
 *                          {@code date} (the current day excluded); null when INSUFFICIENT_DATA
 * @param difference        {@code current - baseline}; null when INSUFFICIENT_DATA
 * @param differencePercent {@code difference / baseline * 100}; null when INSUFFICIENT_DATA or baseline is 0
 * @param sampleCount       number of valid days found in the baseline window
 */
public record RecoveryMetricBaseline(
        RecoveryMetric metric,
        LocalDate date,
        int ageDays,
        double current,
        Double baseline,
        Double difference,
        Double differencePercent,
        int sampleCount,
        int windowDays,
        int minimumSamples,
        BaselineStatus status
) {
}
