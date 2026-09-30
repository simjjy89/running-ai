package com.runningai.training;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;

/**
 * Descriptive training metrics as of a local date. Numbers only: nothing here is a rating, a
 * threshold or a recommendation. All windows are athlete-local calendar days ending on
 * {@code asOfDate}; loads are load minutes (1 minute of normalised activity = 1 load minute).
 * <ul>
 *   <li>{@code acuteLoad}: load of the last 7 days (same as {@code current7DayLoad}).</li>
 *   <li>{@code chronicLoad}: load of the last 28 days divided by 4, i.e. the average weekly load
 *       of the last four weeks (NOT the 28-day total).</li>
 *   <li>{@code acuteChronicRatio}: acuteLoad / chronicLoad, null when chronicLoad is 0.</li>
 *   <li>previous 7 days: the 7 days immediately before the current 7 days (D-13..D-7).</li>
 *   <li>{@code *ChangePercent}: (current - previous) / previous * 100, null when previous is 0.</li>
 *   <li>{@code rampLoad}: current7DayLoad - previous7DayLoad.</li>
 *   <li>{@code monotony}: mean / population standard deviation (divide by N) of the 7 daily loads,
 *       rest days included; null when the deviation is 0. {@code strain}: current7DayLoad * monotony,
 *       null when monotony is null.</li>
 *   <li>{@code activeDays7Days}: days with daily load &gt; 0; {@code restDays7Days} = 7 - active.</li>
 * </ul>
 * These rolling 7-day windows differ from the Monday-to-Sunday calendar week of
 * {@link WeeklyTrainingSummary}. Unavailable values are explicit JSON nulls.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record TrainingState(
        LocalDate asOfDate,
        double acuteLoad,
        double chronicLoad,
        Double acuteChronicRatio,
        double current7DayLoad,
        double previous7DayLoad,
        Double weeklyLoadChangePercent,
        double runningDistance7DaysMeters,
        double previousRunningDistance7DaysMeters,
        Double runningDistanceChangePercent,
        long runningDuration7DaysSeconds,
        long previousRunningDuration7DaysSeconds,
        Double runningDurationChangePercent,
        double rampLoad,
        Double monotony,
        Double strain,
        int activeDays7Days,
        int restDays7Days
) {
}
