package com.runningai.training;

import java.time.LocalDate;

/** Rolling 7 and 28 calendar days ending on (and including) {@code asOfDate}, athlete-local. */
public record TrainingLoadSummary(
        LocalDate asOfDate,
        double load7Days,
        double load28Days,
        double runningDistance7DaysMeters,
        double runningDistance28DaysMeters,
        long runningDuration7DaysSeconds,
        long runningDuration28DaysSeconds,
        int activityCount7Days,
        int activityCount28Days
) {
}
