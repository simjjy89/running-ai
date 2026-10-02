package com.runningai.recovery;

import java.util.function.Function;

/** The recovery metrics that get a personal baseline. Values are read in their stored units. */
public enum RecoveryMetric {

    HRV_LAST_NIGHT_AVG_MS(RecoveryDailyValues::hrvLastNightAvgMs),
    SLEEP_DURATION_SECONDS(RecoveryDailyValues::sleepDurationSeconds),
    SLEEP_SCORE(RecoveryDailyValues::sleepScore),
    RESTING_HEART_RATE_BPM(RecoveryDailyValues::restingHeartRateBpm),
    BODY_BATTERY_HIGHEST(RecoveryDailyValues::bodyBatteryHighest),
    STRESS_AVERAGE(RecoveryDailyValues::stressAverage);

    private final Function<RecoveryDailyValues, ? extends Number> extractor;

    RecoveryMetric(Function<RecoveryDailyValues, ? extends Number> extractor) {
        this.extractor = extractor;
    }

    /** The metric's value on that day, or {@code null} when it was not reported. */
    public Double valueOf(RecoveryDailyValues values) {
        Number value = extractor.apply(values);
        return value == null ? null : value.doubleValue();
    }
}
