package com.runningai.training;

import java.util.EnumSet;
import java.util.List;

import static com.runningai.training.CandidateTrainingType.CROSS_TRAINING;
import static com.runningai.training.CandidateTrainingType.EASY;
import static com.runningai.training.CandidateTrainingType.LONG;
import static com.runningai.training.CandidateTrainingType.QUALITY;
import static com.runningai.training.CandidateTrainingType.RECOVERY;
import static com.runningai.training.CandidateTrainingType.REST;

/**
 * Deterministic, explainable choice of one workout intent from a {@link TrainingDecisionContext}.
 * Plain conditionals in a fixed precedence; the candidate list order is never used as a priority.
 * <p>
 * All numbers below are calendar-scheduling heuristics, not medical, injury-risk or readiness
 * thresholds, and no Phase 4B metric (acute/chronic ratio, monotony, strain) is compared with a limit.
 * <p>
 * Precedence (the first matching rule wins, and the intent must be one of the context candidates):
 * <ol>
 *   <li>REST: a long run yesterday/today and 2+ consecutive active days, or 4+ consecutive active days.</li>
 *   <li>RECOVERY: a long run yesterday/today, or 3+ consecutive active days.</li>
 *   <li>EASY: no activity at all in the history window (limited history).</li>
 *   <li>LONG: a long run is due (last long run 6+ days ago), history is sufficient, at most 2
 *       consecutive active days, load trend not increasing, and the athlete has run within 3 days.</li>
 *   <li>EASY: default.</li>
 *   <li>CROSS_TRAINING: only when EASY is not a candidate.</li>
 * </ol>
 * QUALITY is never selected: there is no quality-session model (see {@link SessionClassification}).
 */
final class WorkoutRecommendationPolicy {

    static final int LONG_RECENT_DAYS = 1;
    static final int REST_STREAK_AFTER_LONG = 2;
    static final int REST_STREAK = 4;
    static final int RECOVERY_STREAK = 3;
    static final int LONG_DUE_MIN_DAYS = 6;
    static final int LONG_MAX_STREAK = 2;
    static final int LONG_MAX_DAYS_SINCE_RUNNING = 3;
    static final int SUFFICIENT_ACTIVE_DAYS = 6;
    static final int MINIMAL_ACTIVE_DAYS = 3;

    private WorkoutRecommendationPolicy() {
    }

    static WorkoutRecommendation recommend(TrainingDecisionContext c) {
        List<CandidateTrainingType> candidates = c.candidateTrainingTypes();
        boolean longRecent = c.daysSinceLongRun() != null && c.daysSinceLongRun() <= LONG_RECENT_DAYS;
        int streak = c.consecutiveActiveDays();
        boolean limited = c.reasons().contains(DecisionReason.LIMITED_HISTORY);
        DataSufficiency sufficiency = sufficiency(c, limited);

        CandidateTrainingType intent;
        RecommendationConfidence confidence;
        String summary;
        boolean longDue = false;
        boolean defaultEasy = false;

        if (((longRecent && streak >= REST_STREAK_AFTER_LONG) || streak >= REST_STREAK) && candidates.contains(REST)) {
            intent = REST;
            confidence = restrictiveConfidence(sufficiency);
            summary = "Several consecutive active days or a recent long run: a rest day is scheduled for today.";
        } else if ((longRecent || streak >= RECOVERY_STREAK) && candidates.contains(RECOVERY)) {
            intent = RECOVERY;
            confidence = restrictiveConfidence(sufficiency);
            summary = "A recent long run or several active days in a row: a very easy recovery session is scheduled before anything harder.";
        } else if (limited && candidates.contains(EASY)) {
            intent = EASY;
            confidence = RecommendationConfidence.LOW;
            summary = "There is little recent activity history, so a conservative easy session is suggested.";
        } else if (isLongDue(c, streak, sufficiency) && candidates.contains(LONG)) {
            intent = LONG;
            longDue = true;
            confidence = RecommendationConfidence.MEDIUM;
            summary = "The last long run was several days ago and recent training is regular, so a long session can be considered.";
        } else if (candidates.contains(EASY)) {
            intent = EASY;
            defaultEasy = true;
            confidence = sufficiency == DataSufficiency.HIGH ? RecommendationConfidence.MEDIUM : RecommendationConfidence.LOW;
            summary = "Recent training context supports a normal easy session.";
        } else if (candidates.contains(CROSS_TRAINING)) {
            intent = CROSS_TRAINING;
            confidence = RecommendationConfidence.LOW;
            summary = "An easy run is not among the candidates, so a cross-training session is suggested.";
        } else {
            // Not reachable with candidate sets produced by TrainingDecisionContextService (EASY is always
            // present unless a restrictive branch above applied). The intent must stay a member of the
            // candidates, so the most conservative candidate is used; when neither is present (for example
            // a QUALITY-only list) this is an invariant violation and no intent outside the candidates is invented.
            if (candidates.contains(RECOVERY)) {
                intent = RECOVERY;
            } else if (candidates.contains(REST)) {
                intent = REST;
            } else {
                throw new IllegalStateException("No selectable workout intent among candidates " + candidates
                        + " (QUALITY is never auto-selected and no candidate outside the list may be returned)");
            }
            confidence = RecommendationConfidence.LOW;
            summary = "No preferred intent is available among the candidates, so the most conservative one is suggested.";
        }

        EnumSet<WorkoutRecommendationReason> reasons = EnumSet.noneOf(WorkoutRecommendationReason.class);
        if (longRecent) {
            reasons.add(WorkoutRecommendationReason.RECENT_LONG_RUN);
        }
        if (streak >= RECOVERY_STREAK) {
            reasons.add(WorkoutRecommendationReason.MULTIPLE_ACTIVE_DAYS);
        }
        if (c.consecutiveRestDays() >= 1) {
            reasons.add(WorkoutRecommendationReason.RECENT_REST);
        }
        switch (c.loadTrend()) {
            case INCREASING -> reasons.add(WorkoutRecommendationReason.LOAD_TREND_INCREASING);
            case STABLE -> reasons.add(WorkoutRecommendationReason.LOAD_TREND_STABLE);
            case DECREASING -> reasons.add(WorkoutRecommendationReason.LOAD_TREND_DECREASING);
            case UNKNOWN -> { }
        }
        if (c.reasons().contains(DecisionReason.LOW_RECENT_ACTIVITY)) {
            reasons.add(WorkoutRecommendationReason.LOW_RECENT_ACTIVITY);
        }
        if (c.reasons().contains(DecisionReason.NO_RECENT_RUNNING)) {
            reasons.add(WorkoutRecommendationReason.NO_RECENT_RUNNING);
        }
        if (c.reasons().contains(DecisionReason.RECENT_CYCLING)) {
            reasons.add(WorkoutRecommendationReason.RECENT_CYCLING);
        }
        if (longDue) {
            reasons.add(WorkoutRecommendationReason.LONG_RUN_DUE);
        }
        if (limited) {
            reasons.add(WorkoutRecommendationReason.LIMITED_HISTORY);
        }
        if (candidates.contains(QUALITY)) {
            reasons.add(WorkoutRecommendationReason.QUALITY_HISTORY_UNAVAILABLE);
        }
        if (defaultEasy) {
            reasons.add(WorkoutRecommendationReason.DEFAULT_EASY);
        }

        return new WorkoutRecommendation(
                c.asOfDate(), intent,
                minMinutes(intent), maxMinutes(intent), intensity(intent),
                confidence, sufficiency,
                List.copyOf(reasons), summary, c);
    }

    /** Long-run-due scheduling rule; a known previous long run is required (no long-run habit, no LONG). */
    private static boolean isLongDue(TrainingDecisionContext c, int streak, DataSufficiency sufficiency) {
        return c.daysSinceLongRun() != null && c.daysSinceLongRun() >= LONG_DUE_MIN_DAYS
                && sufficiency == DataSufficiency.HIGH
                && streak <= LONG_MAX_STREAK
                && c.loadTrend() != LoadTrend.INCREASING
                && c.daysSinceRunning() != null && c.daysSinceRunning() <= LONG_MAX_DAYS_SINCE_RUNNING;
    }

    /**
     * LOW: no history in the window, or fewer than 3 active days in the recent pattern.
     * HIGH: 6+ active days in the recent pattern and a defined load trend (a previous week exists).
     * MEDIUM: everything in between.
     */
    static DataSufficiency sufficiency(TrainingDecisionContext c, boolean limited) {
        long activeDays = c.recentPattern().stream()
                .filter(p -> p.classification() != SessionClassification.REST).count();
        if (limited || activeDays < MINIMAL_ACTIVE_DAYS) {
            return DataSufficiency.LOW;
        }
        if (activeDays >= SUFFICIENT_ACTIVE_DAYS && c.loadTrend() != LoadTrend.UNKNOWN) {
            return DataSufficiency.HIGH;
        }
        return DataSufficiency.MEDIUM;
    }

    /** Rest/recovery rules rest on directly observed recent days; only very sparse history caps them. */
    private static RecommendationConfidence restrictiveConfidence(DataSufficiency sufficiency) {
        return sufficiency == DataSufficiency.LOW ? RecommendationConfidence.MEDIUM : RecommendationConfidence.HIGH;
    }

    static int minMinutes(CandidateTrainingType intent) {
        return switch (intent) {
            case REST -> 0;
            case RECOVERY -> 20;
            case EASY, CROSS_TRAINING -> 30;
            case QUALITY -> 30;
            case LONG -> 75;
        };
    }

    static int maxMinutes(CandidateTrainingType intent) {
        return switch (intent) {
            case REST -> 0;
            case RECOVERY -> 40;
            case EASY, CROSS_TRAINING -> 60;
            case QUALITY -> 70;
            case LONG -> 120;
        };
    }

    static IntensityClass intensity(CandidateTrainingType intent) {
        return switch (intent) {
            case REST -> IntensityClass.NONE;
            case RECOVERY -> IntensityClass.VERY_EASY;
            case EASY, LONG, CROSS_TRAINING -> IntensityClass.EASY;
            case QUALITY -> IntensityClass.HARD;
        };
    }
}
