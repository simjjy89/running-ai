package com.runningai.integration.intervals;

import com.runningai.training.CandidateTrainingType;

import java.time.LocalDate;

/** Operator-facing outcome of one publish. The remote event id is intentionally not exposed. */
public record WorkoutPublishResponse(
        LocalDate date,
        IntervalsPublishOperation operation,
        boolean verified,
        CandidateTrainingType intent,
        int stepCount
) {
}
