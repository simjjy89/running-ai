package com.runningai.training;

import java.time.LocalDate;

/** One ISO week (Monday..Sunday, athlete-local). {@code weekEnd} is the Sunday, inclusive. */
public record WeeklyTrainingSummary(
        LocalDate weekStart,
        LocalDate weekEnd,
        int activityCount,
        double trainingLoadMinutes,
        double runningDistanceMeters,
        long runningDurationSeconds,
        long cyclingDurationSeconds
) {
}
