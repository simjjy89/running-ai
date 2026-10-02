package com.runningai.recovery;

/**
 * One day's normalised recovery metrics, in Garmin's own units (HRV ms, sleep seconds, resting
 * heart rate bpm, Body Battery and stress 0-100). Every field is independently nullable and a
 * {@code null} always means "not reported", never zero or a default. {@code hrvStatus} is Garmin's
 * own label, passed through verbatim.
 */
public record RecoveryDailyValues(
        Double hrvLastNightAvgMs,
        Double hrvWeeklyAvgMs,
        String hrvStatus,
        Integer sleepDurationSeconds,
        Integer sleepScore,
        Integer restingHeartRateBpm,
        Integer bodyBatteryHighest,
        Integer bodyBatteryLowest,
        Integer bodyBatteryCharged,
        Integer bodyBatteryDrained,
        Integer stressAverage,
        Integer stressMax
) {

    public static final RecoveryDailyValues EMPTY =
            new RecoveryDailyValues(null, null, null, null, null, null, null, null, null, null, null, null);

    public boolean isEmpty() {
        return equals(EMPTY);
    }
}
