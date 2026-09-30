package com.runningai.training;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.util.List;

/**
 * The kind of workout to consider today: one intent, a duration range, a qualitative intensity class
 * and the reasons. Not a workout prescription (no exact duration, steps, pace or heart-rate target).
 * The full {@link TrainingDecisionContext} is included so the choice can be checked.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record WorkoutRecommendation(
        LocalDate asOfDate,
        CandidateTrainingType recommendedIntent,
        int durationMinMinutes,
        int durationMaxMinutes,
        IntensityClass intensityClass,
        RecommendationConfidence confidence,
        DataSufficiency dataSufficiency,
        List<WorkoutRecommendationReason> reasons,
        String summary,
        TrainingDecisionContext decisionContext
) {
}
