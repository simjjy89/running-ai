package com.runningai.training;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Turns a {@link TargetedWorkoutPrescription} into a {@link StructuredWorkout}: pure
 * structural re-shaping only (step order preserved, every field copied verbatim). No
 * Intervals.icu/Garmin syntax, no HTTP, no publishing, no idempotency marker, no cue
 * formatting and no persistence -- those belong to later Phase 5C renderer/publisher
 * work. It does not recompute or re-derive any target: every pace/HR/treadmill value
 * was already resolved by {@link WorkoutIntensityTargetService} against the athlete's
 * current intensity profile, and this mapper only re-shapes what it is given.
 * <p>
 * Mapping is total: every {@link TargetedWorkoutSegment} produced by the existing
 * prescription/target pipeline already has well-defined semantics (REST, CROSS_TRAINING
 * qualitative-only, full/partial/absent profile), so there is no impossible input state
 * for this mapper to reject or silently paper over.
 */
@Component
public class StructuredWorkoutMapper {

    public StructuredWorkout map(TargetedWorkoutPrescription prescription) {
        List<StructuredWorkoutStep> steps = prescription.segments().stream()
                .map(StructuredWorkoutMapper::mapStep)
                .toList();
        return new StructuredWorkout(prescription.asOfDate(), prescription.intent(),
                prescription.totalDurationMinutes(), steps);
    }

    private static StructuredWorkoutStep mapStep(TargetedWorkoutSegment segment) {
        return new StructuredWorkoutStep(
                segment.type(),
                segment.durationMinutes(),
                segment.intensityClass(),
                segment.description(),
                segment.primaryTargetType(),
                segment.paceTarget(),
                segment.heartRateTarget(),
                segment.treadmillTarget());
    }
}
