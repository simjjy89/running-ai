package com.runningai.training;

/**
 * One provider-neutral workout step: the same lossless shape as
 * {@link TargetedWorkoutSegment}, but this type is never handed to a renderer
 * that knows Intervals.icu or Garmin syntax directly -- see {@link StructuredWorkout}.
 */
public record StructuredWorkoutStep(
        SegmentType type,
        int durationMinutes,
        IntensityClass intensityClass,
        String description,
        PrimaryTargetType primaryTargetType,
        PaceTarget paceTarget,
        HeartRateTarget heartRateTarget,
        TreadmillTarget treadmillTarget
) {
}
