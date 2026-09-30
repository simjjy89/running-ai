package com.runningai.training;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Derives {@link TrainingState} from {@link TrainingLoadService} daily loads. One 28-day daily
 * series (one activity query, athlete timezone) contains every window needed: the current 7 days,
 * the previous 7 days and the 4-week chronic baseline. Pure measurement: no thresholds, ratings or
 * recommendations, nothing persisted or cached.
 */
@Service
@Transactional(readOnly = true)
public class TrainingStateService {

    private static final int WINDOW_DAYS = 7;
    private static final int CHRONIC_DAYS = 28;

    private final TrainingLoadService trainingLoadService;

    public TrainingStateService(TrainingLoadService trainingLoadService) {
        this.trainingLoadService = trainingLoadService;
    }

    public LocalDate today() {
        return trainingLoadService.today();
    }

    public TrainingState state(LocalDate asOfDate) {
        List<DailyTrainingLoad> days = trainingLoadService.daily(asOfDate.minusDays(CHRONIC_DAYS - 1L), asOfDate);
        return compute(asOfDate, days);
    }

    /** {@code days} must be the 28 consecutive local days ending on asOfDate, oldest first. */
    static TrainingState compute(LocalDate asOfDate, List<DailyTrainingLoad> days) {
        if (days.size() != CHRONIC_DAYS) {
            throw new IllegalArgumentException("expected " + CHRONIC_DAYS + " daily entries but got " + days.size());
        }
        List<DailyTrainingLoad> current = days.subList(CHRONIC_DAYS - WINDOW_DAYS, CHRONIC_DAYS);
        List<DailyTrainingLoad> previous = days.subList(CHRONIC_DAYS - 2 * WINDOW_DAYS, CHRONIC_DAYS - WINDOW_DAYS);

        long currentSeconds = sumSeconds(current);
        long previousSeconds = sumSeconds(previous);
        long chronicSeconds = sumSeconds(days);

        double currentLoad = currentSeconds / 60.0;
        double previousLoad = previousSeconds / 60.0;
        double chronicLoad = chronicSeconds / 60.0 / 4.0;    // average weekly load of the last 4 weeks

        double distanceCurrent = sumRunningDistance(current);
        double distancePrevious = sumRunningDistance(previous);
        long runSecondsCurrent = sumRunningSeconds(current);
        long runSecondsPrevious = sumRunningSeconds(previous);

        int activeDays = (int) current.stream().filter(d -> d.totalDurationSeconds() > 0).count();
        Double monotony = monotony(current);

        return new TrainingState(
                asOfDate,
                currentLoad,
                chronicLoad,
                chronicLoad == 0 ? null : currentLoad / chronicLoad,
                currentLoad,
                previousLoad,
                percentChange(currentLoad, previousLoad),
                distanceCurrent,
                distancePrevious,
                percentChange(distanceCurrent, distancePrevious),
                runSecondsCurrent,
                runSecondsPrevious,
                percentChange(runSecondsCurrent, runSecondsPrevious),
                currentLoad - previousLoad,
                monotony,
                monotony == null ? null : currentLoad * monotony,
                activeDays,
                WINDOW_DAYS - activeDays);
    }

    /** (current - previous) / previous * 100; undefined (null) when there is no previous baseline. */
    private static Double percentChange(double current, double previous) {
        return previous == 0 ? null : (current - previous) / previous * 100.0;
    }

    /**
     * mean / population SD (divide by N) of the daily loads, rest days included. Computed on whole
     * seconds so that identical days give a deviation of exactly 0 (monotony is dimensionless, so the
     * unit does not matter). Null when the deviation is 0 (constant or all-zero week).
     */
    private static Double monotony(List<DailyTrainingLoad> window) {
        int n = window.size();
        double mean = sumSeconds(window) / (double) n;
        double squares = 0;
        for (DailyTrainingLoad d : window) {
            double deviation = d.totalDurationSeconds() - mean;
            squares += deviation * deviation;
        }
        double sd = Math.sqrt(squares / n);
        return sd == 0 ? null : mean / sd;
    }

    private static long sumSeconds(List<DailyTrainingLoad> days) {
        return days.stream().mapToLong(DailyTrainingLoad::totalDurationSeconds).sum();
    }

    private static long sumRunningSeconds(List<DailyTrainingLoad> days) {
        return days.stream().mapToLong(DailyTrainingLoad::runningDurationSeconds).sum();
    }

    private static double sumRunningDistance(List<DailyTrainingLoad> days) {
        return days.stream().mapToDouble(DailyTrainingLoad::runningDistanceMeters).sum();
    }
}
