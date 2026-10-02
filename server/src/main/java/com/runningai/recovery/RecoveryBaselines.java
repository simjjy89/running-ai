package com.runningai.recovery;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/**
 * Personal-baseline comparison of every {@link RecoveryMetric} as of a target date.
 *
 * @param metrics only the metrics that have a current value inside the lookup window; a metric with
 *                no reading at all is absent (never zero-filled)
 * @param days    the stored daily values that were read, by date, so a caller can look up values
 *                recorded alongside a metric (e.g. Garmin's HRV status on the HRV day)
 */
public record RecoveryBaselines(
        LocalDate targetDate,
        Map<RecoveryMetric, RecoveryMetricBaseline> metrics,
        Map<LocalDate, RecoveryDailyValues> days
) {

    public Optional<RecoveryMetricBaseline> metric(RecoveryMetric metric) {
        return Optional.ofNullable(metrics.get(metric));
    }

    /** Stored values for the day {@code baseline} was recorded on. */
    public RecoveryDailyValues dayOf(RecoveryMetricBaseline baseline) {
        return days.getOrDefault(baseline.date(), RecoveryDailyValues.EMPTY);
    }
}
