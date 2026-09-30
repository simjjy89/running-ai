package com.runningai.training;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.util.List;

/**
 * Context for later training decisions: what happened recently and which kinds of training could be
 * considered. Candidates are not final workout prescriptions. Dates and day counts are null when the
 * event is not in the 28-day history window; {@code daysSince*} = asOfDate - last date in calendar days.
 * Quality-session detection is unavailable (see {@link SessionClassification}), so the quality fields stay null.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record TrainingDecisionContext(
        LocalDate asOfDate,
        TrainingState trainingState,
        List<DailyTrainingPattern> recentPattern,
        LocalDate lastRunningDate,
        Integer daysSinceRunning,
        LocalDate lastActiveDate,
        Integer daysSinceActive,
        LocalDate lastLongRunDate,
        Integer daysSinceLongRun,
        boolean qualityDetectionAvailable,
        LocalDate lastQualityDate,
        Integer daysSinceQuality,
        int consecutiveActiveDays,
        int consecutiveRestDays,
        LoadTrend loadTrend,
        List<CandidateTrainingType> candidateTrainingTypes,
        List<DecisionReason> reasons
) {
}
