package com.runningai.training;

/** One part of a workout: whole minutes and a qualitative intensity only (no pace, heart-rate or incline target). */
public record WorkoutSegment(
        SegmentType type,
        int durationMinutes,
        IntensityClass intensityClass,
        String description
) {
}
