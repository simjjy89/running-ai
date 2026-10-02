package com.runningai.recovery;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Compares each recovery metric's latest value with the athlete's own recent history.
 * <p>
 * Calculation only (Phase 6F rule): it reports current value, baseline, difference, difference
 * percentage and sample count, and says {@link BaselineStatus#INSUFFICIENT_DATA} when history is too
 * thin. It never labels a value as good, bad, low or high and never influences which workout is
 * chosen - that is the AI coach's job.
 * <ul>
 *   <li>Current value: the most recent day with a value within the {@value #WINDOW_DAYS} days ending on
 *       the target date (so a value can be up to {@code WINDOW_DAYS - 1} days old; its age is reported).</li>
 *   <li>Baseline: arithmetic mean of the valid values in the {@value #WINDOW_DAYS} days <em>before</em> the
 *       current value's day. The current day is excluded so it is not compared with itself.</li>
 *   <li>At least {@value #MINIMUM_SAMPLES} valid days are required, otherwise INSUFFICIENT_DATA.</li>
 * </ul>
 * Days without a value are skipped, never treated as zero.
 */
@Service
public class RecoveryBaselineService {

    public static final int WINDOW_DAYS = 28;
    public static final int MINIMUM_SAMPLES = 7;

    private final RecoverySnapshotService snapshotService;

    public RecoveryBaselineService(RecoverySnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    public RecoveryBaselines baselines(LocalDate targetDate) {
        // current lookup reaches back WINDOW_DAYS - 1 days, its baseline another WINDOW_DAYS before that
        LocalDate from = targetDate.minusDays(2L * WINDOW_DAYS - 1);
        Map<LocalDate, RecoveryDailyValues> days = new TreeMap<>();
        snapshotService.between(from, targetDate).forEach(s -> days.put(s.getRecoveryDate(), s.values()));
        return compute(targetDate, days);
    }

    /** Pure computation over already-loaded daily values (dates after {@code targetDate} are ignored). */
    public static RecoveryBaselines compute(LocalDate targetDate, Map<LocalDate, RecoveryDailyValues> days) {
        TreeMap<LocalDate, RecoveryDailyValues> sorted = new TreeMap<>(days);
        Map<RecoveryMetric, RecoveryMetricBaseline> metrics = new EnumMap<>(RecoveryMetric.class);
        for (RecoveryMetric metric : RecoveryMetric.values()) {
            LocalDate currentDate = latestDateWithValue(sorted, metric, targetDate);
            if (currentDate != null) {
                metrics.put(metric, baseline(sorted, metric, targetDate, currentDate));
            }
        }
        return new RecoveryBaselines(targetDate, Collections.unmodifiableMap(metrics),
                Collections.unmodifiableMap(sorted.headMap(targetDate, true)));
    }

    private static LocalDate latestDateWithValue(TreeMap<LocalDate, RecoveryDailyValues> days,
                                                 RecoveryMetric metric, LocalDate targetDate) {
        LocalDate earliest = targetDate.minusDays(WINDOW_DAYS - 1);
        for (Map.Entry<LocalDate, RecoveryDailyValues> e : days.subMap(earliest, true, targetDate, true)
                .descendingMap().entrySet()) {
            if (metric.valueOf(e.getValue()) != null) {
                return e.getKey();
            }
        }
        return null;
    }

    private static RecoveryMetricBaseline baseline(TreeMap<LocalDate, RecoveryDailyValues> days, RecoveryMetric metric,
                                                   LocalDate targetDate, LocalDate currentDate) {
        double current = metric.valueOf(days.get(currentDate));
        int ageDays = (int) ChronoUnit.DAYS.between(currentDate, targetDate);

        double sum = 0;
        int samples = 0;
        for (RecoveryDailyValues v : days.subMap(currentDate.minusDays(WINDOW_DAYS), true, currentDate, false).values()) {
            Double value = metric.valueOf(v);
            if (value != null) {
                sum += value;
                samples++;
            }
        }

        if (samples < MINIMUM_SAMPLES) {
            return new RecoveryMetricBaseline(metric, currentDate, ageDays, current, null, null, null,
                    samples, WINDOW_DAYS, MINIMUM_SAMPLES, BaselineStatus.INSUFFICIENT_DATA);
        }
        double mean = sum / samples;
        double difference = current - mean;
        Double percent = mean == 0 ? null : round1(difference / mean * 100);
        return new RecoveryMetricBaseline(metric, currentDate, ageDays, current, round1(mean), round1(difference),
                percent, samples, WINDOW_DAYS, MINIMUM_SAMPLES, BaselineStatus.AVAILABLE);
    }

    private static double round1(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
