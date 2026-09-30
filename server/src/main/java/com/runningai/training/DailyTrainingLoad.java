package com.runningai.training;

import java.time.LocalDate;

/**
 * One athlete-local calendar day of training. {@code trainingLoadMinutes} is the total duration of all
 * supported activities in minutes (1 minute = 1 load minute); running metrics count RUN and
 * TREADMILL_RUN only; distance is metres.
 */
public record DailyTrainingLoad(
        LocalDate date,
        int activityCount,
        long totalDurationSeconds,
        double trainingLoadMinutes,
        double runningDistanceMeters,
        long runningDurationSeconds,
        long cyclingDurationSeconds
) {
}
