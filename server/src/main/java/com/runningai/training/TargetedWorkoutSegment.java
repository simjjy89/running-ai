package com.runningai.training;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A {@link WorkoutSegment} with numeric intensity targets layered on top (or the
 * qualitative/none fallback). No interval, repeat or distance-target fields yet.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record TargetedWorkoutSegment(
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
