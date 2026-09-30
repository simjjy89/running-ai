package com.runningai.training;

import java.time.LocalDate;
import java.util.List;

/**
 * {@link TargetedWorkoutPrescription} re-expressed as a provider-neutral, ordered
 * sequence of steps: the intermediate representation a future renderer (Phase 5C-2's
 * {@code IntervalsWorkoutRenderer}) consumes instead of depending on the
 * training/recommendation domain directly.
 * <p>
 * This type is provider-neutral and contains no Intervals.icu or Garmin rendering
 * syntax (no workout-builder tokens, no {@code hr=1s}, no cue text, no HTTP/publish
 * concepts). All numeric targets stay in RunningAI's own canonical units exactly as
 * {@link WorkoutIntensityTargetService} computed them: pace in seconds/km, heart rate
 * in both %LTHR and bpm, treadmill speed in km/h and incline in percent. Rendering
 * those into a specific provider's text/token syntax is a later phase's job, not this
 * type's.
 */
public record StructuredWorkout(
        LocalDate asOfDate,
        CandidateTrainingType intent,
        int totalDurationMinutes,
        List<StructuredWorkoutStep> steps
) {
}
