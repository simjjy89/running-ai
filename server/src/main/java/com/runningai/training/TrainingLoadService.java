package com.runningai.training;

import com.runningai.activity.Activity;
import com.runningai.activity.ActivityRepository;
import com.runningai.activity.ActivityType;
import com.runningai.athlete.AthleteService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Derived training load computed on the fly from normalised {@link Activity} rows only (never from
 * raw payloads, Garmin or the connector). Nothing is persisted or cached.
 * <p>
 * Definition: trainingLoadMinutes = total activity duration in minutes (1 minute = 1 load minute),
 * summed over all supported types (RUN, TREADMILL_RUN, INDOOR_CYCLING). Running metrics cover RUN and
 * TREADMILL_RUN; cycling duration covers INDOOR_CYCLING. Distance stays in metres; a missing distance
 * counts as 0 but the activity itself is still counted.
 * <p>
 * Days and weeks follow the athlete's local calendar (Athlete.timezone); a local day is converted to the
 * UTC instant range {@code [startOfDay, startOfNextDay)}. Weeks are ISO (Monday 00:00 to next Monday).
 */
@Service
@Transactional(readOnly = true)
public class TrainingLoadService {

    private final ActivityRepository activityRepository;
    private final AthleteService athleteService;
    private final Clock clock;

    public TrainingLoadService(ActivityRepository activityRepository, AthleteService athleteService, Clock clock) {
        this.activityRepository = activityRepository;
        this.athleteService = athleteService;
        this.clock = clock;
    }

    /** Today's date in the athlete timezone. */
    public LocalDate today() {
        return LocalDate.now(clock.withZone(athleteZone()));
    }

    public TrainingLoadSummary summary(LocalDate asOfDate) {
        return summary(asOfDate, athleteZone());
    }

    public WeeklyTrainingSummary weekly(LocalDate date) {
        return weekly(date, athleteZone());
    }

    /** One entry per local day in {@code [fromDate, toDateInclusive]}; days without activity appear as zeros. */
    public List<DailyTrainingLoad> daily(LocalDate fromDate, LocalDate toDateInclusive) {
        return daily(fromDate, toDateInclusive, athleteZone());
    }

    TrainingLoadSummary summary(LocalDate asOfDate, ZoneId zone) {
        // One 28-day query; the 7-day window is its trailing part.
        List<DailyTrainingLoad> days = daily(asOfDate.minusDays(27), asOfDate, zone);
        LocalDate sevenFrom = asOfDate.minusDays(6);
        Totals w7 = new Totals();
        Totals w28 = new Totals();
        for (DailyTrainingLoad d : days) {
            w28.add(d);
            if (!d.date().isBefore(sevenFrom)) {
                w7.add(d);
            }
        }
        return new TrainingLoadSummary(asOfDate,
                w7.loadMinutes(), w28.loadMinutes(),
                w7.runningDistanceMeters, w28.runningDistanceMeters,
                w7.runningDurationSeconds, w28.runningDurationSeconds,
                w7.activityCount, w28.activityCount);
    }

    WeeklyTrainingSummary weekly(LocalDate date, ZoneId zone) {
        LocalDate weekStart = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate weekEnd = weekStart.plusDays(6);
        Totals t = new Totals();
        daily(weekStart, weekEnd, zone).forEach(t::add);
        return new WeeklyTrainingSummary(weekStart, weekEnd, t.activityCount, t.loadMinutes(),
                t.runningDistanceMeters, t.runningDurationSeconds, t.cyclingDurationSeconds);
    }

    List<DailyTrainingLoad> daily(LocalDate fromDate, LocalDate toDateInclusive, ZoneId zone) {
        if (toDateInclusive.isBefore(fromDate)) {
            throw new IllegalArgumentException("toDateInclusive must not be before fromDate");
        }
        Instant from = fromDate.atStartOfDay(zone).toInstant();
        Instant to = toDateInclusive.plusDays(1).atStartOfDay(zone).toInstant();   // exclusive
        Long athleteId = athleteService.getDefaultAthlete().getId();

        Map<LocalDate, Totals> byDay = new TreeMap<>();
        for (LocalDate d = fromDate; !d.isAfter(toDateInclusive); d = d.plusDays(1)) {
            byDay.put(d, new Totals());
        }
        for (Activity a : activityRepository.findByAthleteIdAndStartedAtGreaterThanEqualAndStartedAtLessThan(athleteId, from, to)) {
            Totals t = byDay.get(a.getStartedAt().atZone(zone).toLocalDate());
            if (t != null) {          // always true for rows inside the queried range
                t.add(a);
            }
        }
        List<DailyTrainingLoad> result = new ArrayList<>(byDay.size());
        byDay.forEach((date, t) -> result.add(new DailyTrainingLoad(date, t.activityCount, t.totalDurationSeconds,
                t.loadMinutes(), t.runningDistanceMeters, t.runningDurationSeconds, t.cyclingDurationSeconds)));
        return result;
    }

    private ZoneId athleteZone() {
        return ZoneId.of(athleteService.getDefaultAthlete().getTimezone());
    }

    private static boolean isRunning(ActivityType type) {
        return type == ActivityType.RUN || type == ActivityType.TREADMILL_RUN;
    }

    /** Mutable accumulator; durations stay in seconds until the final minutes conversion (no early rounding). */
    private static final class Totals {
        int activityCount;
        long totalDurationSeconds;
        double runningDistanceMeters;
        long runningDurationSeconds;
        long cyclingDurationSeconds;

        void add(Activity a) {
            activityCount++;
            totalDurationSeconds += a.getDurationSeconds();
            if (isRunning(a.getActivityType())) {
                runningDurationSeconds += a.getDurationSeconds();
                if (a.getDistanceMeters() != null) {
                    runningDistanceMeters += a.getDistanceMeters();
                }
            } else if (a.getActivityType() == ActivityType.INDOOR_CYCLING) {
                cyclingDurationSeconds += a.getDurationSeconds();
            }
        }

        void add(DailyTrainingLoad d) {
            activityCount += d.activityCount();
            totalDurationSeconds += d.totalDurationSeconds();
            runningDistanceMeters += d.runningDistanceMeters();
            runningDurationSeconds += d.runningDurationSeconds();
            cyclingDurationSeconds += d.cyclingDurationSeconds();
        }

        double loadMinutes() {
            return totalDurationSeconds / 60.0;
        }
    }
}
