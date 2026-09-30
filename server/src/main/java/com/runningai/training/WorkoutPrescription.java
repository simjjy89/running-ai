package com.runningai.training;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.util.List;

/**
 * Exact-duration, qualitative workout structure for the recommended intent. Not yet a targeted workout:
 * no pace, heart-rate, LTHR, incline, interval or Garmin/Intervals.icu fields. The source
 * {@link WorkoutRecommendation} is included so the prescription can be traced back.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record WorkoutPrescription(
        LocalDate asOfDate,
        CandidateTrainingType intent,
        int totalDurationMinutes,
        List<WorkoutSegment> segments,
        String summary,
        WorkoutRecommendation recommendation
) {
}
