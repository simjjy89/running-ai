package com.runningai.training;

import com.runningai.athlete.AthleteIntensityProfileResponse;

import java.util.List;

/**
 * Layers numeric intensity targets onto a {@link WorkoutPrescription}. A REST segment
 * always gets {@link PrimaryTargetType#NONE}; a {@link CandidateTrainingType#CROSS_TRAINING}
 * prescription always gets {@link PrimaryTargetType#QUALITATIVE} for every segment, because
 * the athlete's intensity profile is a *running* threshold profile and must not be applied
 * to a different modality. Every other (running) segment prefers a pace target over a
 * heart-rate target when both are available (see {@link RunningIntensityTargetPolicy}); a
 * profile with neither value, or an unsupported intensity class, falls back to
 * {@link PrimaryTargetType#QUALITATIVE} without failing the request.
 */
final class WorkoutIntensityTargetPolicy {

    private WorkoutIntensityTargetPolicy() {
    }

    static TargetedWorkoutPrescription apply(WorkoutPrescription prescription, AthleteIntensityProfileResponse profile) {
        CandidateTrainingType intent = prescription.intent();
        Integer thresholdPace = profile.lactateThresholdPaceSecondsPerKm();
        Integer lthr = profile.lactateThresholdHeartRateBpm();

        List<TargetedWorkoutSegment> segments = prescription.segments().stream()
                .map(segment -> toTargetedSegment(segment, intent, thresholdPace, lthr))
                .toList();

        return new TargetedWorkoutPrescription(prescription.asOfDate(), intent, prescription.totalDurationMinutes(),
                segments, targetAvailability(intent, thresholdPace, lthr), profile, prescription);
    }

    private static TargetedWorkoutSegment toTargetedSegment(WorkoutSegment segment, CandidateTrainingType intent,
                                                             Integer thresholdPace, Integer lthr) {
        if (segment.type() == SegmentType.REST) {
            return withoutNumericTarget(segment, PrimaryTargetType.NONE);
        }
        if (intent == CandidateTrainingType.CROSS_TRAINING) {
            return withoutNumericTarget(segment, PrimaryTargetType.QUALITATIVE);
        }

        PaceTarget pace = RunningIntensityTargetPolicy.paceTarget(segment.intensityClass(), thresholdPace);
        HeartRateTarget heartRate = RunningIntensityTargetPolicy.heartRateTarget(segment.intensityClass(), lthr);
        TreadmillTarget treadmill = RunningIntensityTargetPolicy.treadmillTarget(segment.type(), pace);

        PrimaryTargetType primary = pace != null ? PrimaryTargetType.PACE
                : heartRate != null ? PrimaryTargetType.HEART_RATE
                : PrimaryTargetType.QUALITATIVE;

        return new TargetedWorkoutSegment(segment.type(), segment.durationMinutes(), segment.intensityClass(),
                segment.description(), primary, pace, heartRate, treadmill);
    }

    private static TargetedWorkoutSegment withoutNumericTarget(WorkoutSegment segment, PrimaryTargetType primary) {
        return new TargetedWorkoutSegment(segment.type(), segment.durationMinutes(), segment.intensityClass(),
                segment.description(), primary, null, null, null);
    }

    private static TargetAvailability targetAvailability(CandidateTrainingType intent, Integer thresholdPace, Integer lthr) {
        if (intent == CandidateTrainingType.CROSS_TRAINING || intent == CandidateTrainingType.REST) {
            return TargetAvailability.QUALITATIVE_ONLY;
        }
        boolean paceAvailable = thresholdPace != null;
        boolean heartRateAvailable = lthr != null;
        if (paceAvailable && heartRateAvailable) {
            return TargetAvailability.FULL;
        }
        if (paceAvailable) {
            return TargetAvailability.PACE_ONLY;
        }
        if (heartRateAvailable) {
            return TargetAvailability.HEART_RATE_ONLY;
        }
        return TargetAvailability.QUALITATIVE_ONLY;
    }
}
