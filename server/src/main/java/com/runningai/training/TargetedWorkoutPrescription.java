package com.runningai.training;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.runningai.athlete.AthleteIntensityProfileResponse;

import java.time.LocalDate;
import java.util.List;

/**
 * {@link WorkoutPrescription} with pace / %LTHR heart-rate / treadmill speed and
 * incline targets layered on top. The source prescription and the profile used to
 * derive the targets are both included so the result can be traced and no
 * targets are ever persisted (they are recomputed from the current
 * {@link AthleteIntensityProfileResponse} every call, even for a historical
 * {@code asOfDate} -- see {@link WorkoutIntensityTargetService}).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record TargetedWorkoutPrescription(
        LocalDate asOfDate,
        CandidateTrainingType intent,
        int totalDurationMinutes,
        List<TargetedWorkoutSegment> segments,
        TargetAvailability targetAvailability,
        AthleteIntensityProfileResponse profile,
        WorkoutPrescription prescription
) {
}
