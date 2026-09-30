package com.runningai.training;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;

/** One athlete-local day of the recent pattern. Load is duration based (1 minute = 1 load minute). */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DailyTrainingPattern(
        LocalDate date,
        SessionClassification classification,
        ClassificationReason classificationReason,
        int activityCount,
        double totalLoadMinutes,
        long runningDurationSeconds,
        double runningDistanceMeters,
        long cyclingDurationSeconds
) {
}
