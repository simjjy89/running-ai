package com.runningai.integration.garmin;

import com.runningai.recovery.RecoveryDailyValues;

import java.util.List;
import java.util.function.Predicate;

/**
 * The five metric groups the Garmin connector's {@code GET /recovery} returns, and the single
 * definition of what it means for one of them to have delivered something.
 * <p>
 * One definition rather than two: {@link GarminRecoveryMapper} uses it to decide which metrics a
 * fresh payload delivered, and the coverage report uses the same predicate against already-stored
 * values. A metric group is "present" when at least one of its fields carries a value - a group is
 * never counted as present because some other group's field happened to arrive.
 * <p>
 * These are COLLECTION groups, not baseline metrics: {@link com.runningai.recovery.RecoveryMetric}
 * is the separate, narrower list of fields that get a personal baseline.
 */
public enum GarminRecoveryMetricGroup {

    HRV("hrv", v -> v.hrvLastNightAvgMs() != null || v.hrvWeeklyAvgMs() != null || v.hrvStatus() != null),
    SLEEP("sleep", v -> v.sleepDurationSeconds() != null || v.sleepScore() != null),
    RESTING_HEART_RATE("restingHeartRate", v -> v.restingHeartRateBpm() != null),
    BODY_BATTERY("bodyBattery", v -> v.bodyBatteryHighest() != null || v.bodyBatteryLowest() != null
            || v.bodyBatteryCharged() != null || v.bodyBatteryDrained() != null),
    STRESS("stress", v -> v.stressAverage() != null || v.stressMax() != null);

    /** Connector payload order, which is also the order every report lists them in. */
    public static final List<GarminRecoveryMetricGroup> ALL = List.of(values());

    private final String connectorName;
    private final Predicate<RecoveryDailyValues> present;

    GarminRecoveryMetricGroup(String connectorName, Predicate<RecoveryDailyValues> present) {
        this.connectorName = connectorName;
        this.present = present;
    }

    /** The key this group has in the connector payload and in every API response that names it. */
    public String connectorName() {
        return connectorName;
    }

    /** Whether these values carry at least one reading for this group. */
    public boolean isPresentIn(RecoveryDailyValues values) {
        return present.test(values);
    }
}
