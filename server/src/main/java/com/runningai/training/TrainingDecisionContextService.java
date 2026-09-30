package com.runningai.training;

import com.runningai.activity.Activity;
import com.runningai.activity.ActivityRepository;
import com.runningai.activity.ActivityType;
import com.runningai.athlete.AthleteService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds the {@link TrainingDecisionContext}: recent pattern, last-event dates, consecutive counters,
 * load trend and candidate training types. Context and candidate generation only: no workout
 * prescription, readiness or risk rating, and nothing persisted or cached.
 * <p>
 * One 28-day activity query (athlete timezone, upper bound exclusive at the start of the day after
 * asOfDate, so nothing after asOfDate can leak in) feeds everything in memory: the daily series, the
 * {@link TrainingState} (reusing {@link TrainingStateService#compute}) and the per-day classification.
 * Candidate rules are plain conditionals; the output order is the enum declaration order, so it is
 * deterministic and duplicate-free by construction.
 */
@Service
@Transactional(readOnly = true)
public class TrainingDecisionContextService {

    /** History window in calendar days; equals the 28-day window {@link TrainingState} is defined over. */
    static final int HISTORY_DAYS = 28;
    private static final int LONG_RECENT_DAYS = 1;
    private static final int MULTIPLE_ACTIVE_DAYS = 3;
    private static final int RECENT_CYCLING_DAYS = 1;
    private static final int LOW_ACTIVITY_WINDOW_DAYS = 7;

    private final ActivityRepository activityRepository;
    private final AthleteService athleteService;
    private final TrainingLoadService trainingLoadService;
    private final TrainingProperties properties;

    public TrainingDecisionContextService(ActivityRepository activityRepository,
                                          AthleteService athleteService,
                                          TrainingLoadService trainingLoadService,
                                          TrainingProperties properties) {
        this.activityRepository = activityRepository;
        this.athleteService = athleteService;
        this.trainingLoadService = trainingLoadService;
        this.properties = properties;
    }

    public LocalDate today() {
        return trainingLoadService.today();
    }

    public TrainingDecisionContext context(LocalDate asOfDate) {
        var athlete = athleteService.getDefaultAthlete();
        ZoneId zone = ZoneId.of(athlete.getTimezone());
        LocalDate historyFrom = asOfDate.minusDays(HISTORY_DAYS - 1L);
        Instant from = historyFrom.atStartOfDay(zone).toInstant();
        Instant to = asOfDate.plusDays(1).atStartOfDay(zone).toInstant();   // exclusive: no look-ahead
        List<Activity> activities = activityRepository
                .findByAthleteIdAndStartedAtGreaterThanEqualAndStartedAtLessThan(athlete.getId(), from, to);
        return compute(asOfDate, zone, activities);
    }

    TrainingDecisionContext compute(LocalDate asOfDate, ZoneId zone, List<Activity> activities) {
        LocalDate historyFrom = asOfDate.minusDays(HISTORY_DAYS - 1L);
        List<DailyTrainingLoad> days = trainingLoadService.aggregate(historyFrom, asOfDate, zone, activities);
        TrainingState state = TrainingStateService.compute(asOfDate, days);

        long longRunSeconds = properties.classification().longRunMinDuration().toSeconds();
        Set<LocalDate> longRunDays = new HashSet<>();
        for (Activity a : activities) {
            LocalDate day = a.getStartedAt().atZone(zone).toLocalDate();
            if (isRunning(a.getActivityType()) && a.getDurationSeconds() >= longRunSeconds
                    && !day.isBefore(historyFrom) && !day.isAfter(asOfDate)) {
                longRunDays.add(day);
            }
        }

        List<DailyTrainingPattern> classified = new ArrayList<>(days.size());
        for (DailyTrainingLoad d : days) {
            classified.add(classify(d, longRunDays.contains(d.date())));
        }
        int patternDays = properties.decision().patternDays();
        List<DailyTrainingPattern> recentPattern = List.copyOf(classified.subList(HISTORY_DAYS - patternDays, HISTORY_DAYS));

        LocalDate lastRunning = null;
        LocalDate lastActive = null;
        LocalDate lastLong = null;
        for (int i = days.size() - 1; i >= 0; i--) {      // newest first; first hit wins
            DailyTrainingLoad d = days.get(i);
            if (lastActive == null && isActive(d)) {
                lastActive = d.date();
            }
            if (lastRunning == null && d.runningDurationSeconds() > 0) {
                lastRunning = d.date();
            }
            if (lastLong == null && longRunDays.contains(d.date())) {
                lastLong = d.date();
            }
        }

        int consecutiveActive = 0;
        for (int i = days.size() - 1; i >= 0 && isActive(days.get(i)); i--) {
            consecutiveActive++;
        }
        int consecutiveRest = 0;
        for (int i = days.size() - 1; i >= 0 && !isActive(days.get(i)); i--) {
            consecutiveRest++;
        }

        LoadTrend trend = trend(state.weeklyLoadChangePercent(), properties.decision().stableBandPercent());
        Integer daysSinceRunning = daysSince(asOfDate, lastRunning);
        Integer daysSinceActive = daysSince(asOfDate, lastActive);
        Integer daysSinceLong = daysSince(asOfDate, lastLong);

        boolean longRecent = daysSinceLong != null && daysSinceLong <= LONG_RECENT_DAYS;
        boolean manyActiveDays = consecutiveActive >= MULTIPLE_ACTIVE_DAYS;
        boolean limitedHistory = lastActive == null;

        EnumSet<DecisionReason> reasons = EnumSet.noneOf(DecisionReason.class);
        if (limitedHistory) {
            reasons.add(DecisionReason.LIMITED_HISTORY);
        }
        if (longRecent) {
            reasons.add(DecisionReason.LONG_RUN_RECENT);
        }
        if (manyActiveDays) {
            reasons.add(DecisionReason.MULTIPLE_ACTIVE_DAYS);
        }
        if (consecutiveRest >= 1) {
            reasons.add(DecisionReason.REST_DAY_RECENT);
        }
        if (trend == LoadTrend.INCREASING) {
            reasons.add(DecisionReason.LOAD_INCREASING);
        } else if (trend == LoadTrend.DECREASING) {
            reasons.add(DecisionReason.LOAD_DECREASING);
        }
        if (!limitedHistory && daysSinceActive > LOW_ACTIVITY_WINDOW_DAYS - 1) {
            reasons.add(DecisionReason.LOW_RECENT_ACTIVITY);
        }
        if (lastRunning == null) {
            reasons.add(DecisionReason.NO_RECENT_RUNNING);
        }
        if (recentCycling(days)) {
            reasons.add(DecisionReason.RECENT_CYCLING);
        }

        EnumSet<CandidateTrainingType> candidates = EnumSet.noneOf(CandidateTrainingType.class);
        if (limitedHistory) {
            candidates.add(CandidateTrainingType.EASY);
            candidates.add(CandidateTrainingType.REST);
            candidates.add(CandidateTrainingType.CROSS_TRAINING);
        } else if (longRecent || manyActiveDays) {
            candidates.add(CandidateTrainingType.REST);
            candidates.add(CandidateTrainingType.RECOVERY);
            candidates.add(CandidateTrainingType.EASY);
        } else if (consecutiveRest >= 1) {
            candidates.add(CandidateTrainingType.EASY);
            candidates.add(CandidateTrainingType.QUALITY);
            candidates.add(CandidateTrainingType.LONG);
            candidates.add(CandidateTrainingType.CROSS_TRAINING);
        } else {
            // Active on the as-of day (1-2 active days so far), no recent long run: stay conservative.
            candidates.add(CandidateTrainingType.REST);
            candidates.add(CandidateTrainingType.RECOVERY);
            candidates.add(CandidateTrainingType.EASY);
            candidates.add(CandidateTrainingType.CROSS_TRAINING);
        }

        return new TrainingDecisionContext(
                asOfDate,
                state,
                recentPattern,
                lastRunning, daysSinceRunning,
                lastActive, daysSinceActive,
                lastLong, daysSinceLong,
                false, null, null,
                consecutiveActive,
                consecutiveRest,
                trend,
                List.copyOf(candidates),
                List.copyOf(reasons));
    }

    /**
     * LONG when a single run reached the configured duration; otherwise EASY_OR_GENERAL with any run;
     * INDOOR_CYCLING for cycling only; REST for no load. QUALITY_CANDIDATE is not assignable from the
     * normalised fields and is never produced.
     */
    private static DailyTrainingPattern classify(DailyTrainingLoad d, boolean hasLongRun) {
        SessionClassification classification;
        ClassificationReason reason;
        if (!isActive(d)) {
            classification = SessionClassification.REST;
            reason = ClassificationReason.NO_ACTIVITY;
        } else if (hasLongRun) {
            classification = SessionClassification.LONG;
            reason = ClassificationReason.DURATION_THRESHOLD;
        } else if (d.runningDurationSeconds() > 0) {
            classification = SessionClassification.EASY_OR_GENERAL;
            reason = ClassificationReason.RUNNING_ACTIVITY;
        } else {
            classification = SessionClassification.INDOOR_CYCLING;
            reason = ClassificationReason.CYCLING_ONLY;
        }
        return new DailyTrainingPattern(d.date(), classification, reason, d.activityCount(),
                d.trainingLoadMinutes(), d.runningDurationSeconds(), d.runningDistanceMeters(),
                d.cyclingDurationSeconds());
    }

    /** Descriptive label from the 4B weekly load change; the band is configuration, not a safety limit. */
    static LoadTrend trend(Double weeklyLoadChangePercent, double stableBandPercent) {
        if (weeklyLoadChangePercent == null) {
            return LoadTrend.UNKNOWN;
        }
        if (weeklyLoadChangePercent > stableBandPercent) {
            return LoadTrend.INCREASING;
        }
        if (weeklyLoadChangePercent < -stableBandPercent) {
            return LoadTrend.DECREASING;
        }
        return LoadTrend.STABLE;
    }

    private static boolean recentCycling(List<DailyTrainingLoad> days) {
        int last = days.size() - 1;
        for (int i = last; i >= Math.max(0, last - RECENT_CYCLING_DAYS); i--) {
            if (days.get(i).cyclingDurationSeconds() > 0) {
                return true;
            }
        }
        return false;
    }

    private static Integer daysSince(LocalDate asOf, LocalDate last) {
        return last == null ? null : (int) ChronoUnit.DAYS.between(last, asOf);
    }

    private static boolean isActive(DailyTrainingLoad d) {
        return d.totalDurationSeconds() > 0;
    }

    private static boolean isRunning(ActivityType type) {
        return type == ActivityType.RUN || type == ActivityType.TREADMILL_RUN;
    }
}
