package com.runningai.training;

import com.runningai.athlete.AthleteIntensityProfileResponse;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure rule tests on hand-built prescriptions (no database, no HTTP). All profile values are synthetic. */
class WorkoutIntensityTargetPolicyTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 30);
    private static final AthleteIntensityProfileResponse ABSENT = AthleteIntensityProfileResponse.empty();
    private static final AthleteIntensityProfileResponse PACE_ONLY =
            new AthleteIntensityProfileResponse(true, null, 300);
    private static final AthleteIntensityProfileResponse HR_ONLY =
            new AthleteIntensityProfileResponse(true, 160, null);
    private static final AthleteIntensityProfileResponse FULL =
            new AthleteIntensityProfileResponse(true, 160, 300);

    private static WorkoutRecommendation dummyRecommendation(CandidateTrainingType intent) {
        return new WorkoutRecommendation(AS_OF, intent, 0, 999, IntensityClass.EASY,
                RecommendationConfidence.MEDIUM, DataSufficiency.MEDIUM, List.of(), "test", null);
    }

    private static WorkoutPrescription easyPrescription() {
        List<WorkoutSegment> segments = List.of(
                new WorkoutSegment(SegmentType.WARM_UP, 10, IntensityClass.VERY_EASY, "warm-up"),
                new WorkoutSegment(SegmentType.MAIN, 30, IntensityClass.EASY, "main"),
                new WorkoutSegment(SegmentType.COOL_DOWN, 5, IntensityClass.VERY_EASY, "cool-down"));
        return new WorkoutPrescription(AS_OF, CandidateTrainingType.EASY, 45, segments, "45-minute easy",
                dummyRecommendation(CandidateTrainingType.EASY));
    }

    private static WorkoutPrescription restPrescription() {
        List<WorkoutSegment> segments = List.of(new WorkoutSegment(SegmentType.REST, 0, IntensityClass.NONE, "rest"));
        return new WorkoutPrescription(AS_OF, CandidateTrainingType.REST, 0, segments, "rest day",
                dummyRecommendation(CandidateTrainingType.REST));
    }

    private static WorkoutPrescription crossTrainingPrescription() {
        List<WorkoutSegment> segments = List.of(
                new WorkoutSegment(SegmentType.WARM_UP, 5, IntensityClass.VERY_EASY, "warm-up"),
                new WorkoutSegment(SegmentType.MAIN, 35, IntensityClass.EASY, "main"),
                new WorkoutSegment(SegmentType.COOL_DOWN, 5, IntensityClass.VERY_EASY, "cool-down"));
        return new WorkoutPrescription(AS_OF, CandidateTrainingType.CROSS_TRAINING, 45, segments, "45-minute cross-training",
                dummyRecommendation(CandidateTrainingType.CROSS_TRAINING));
    }

    @Test
    void profileAbsentFallsBackToQualitativeButIncludesIncline() {
        TargetedWorkoutPrescription t = WorkoutIntensityTargetPolicy.apply(easyPrescription(), ABSENT);

        assertThat(t.targetAvailability()).isEqualTo(TargetAvailability.QUALITATIVE_ONLY);
        for (TargetedWorkoutSegment s : t.segments()) {
            assertThat(s.primaryTargetType()).isEqualTo(PrimaryTargetType.QUALITATIVE);
            assertThat(s.paceTarget()).isNull();
            assertThat(s.heartRateTarget()).isNull();
            assertThat(s.treadmillTarget()).isNotNull();
            assertThat(s.treadmillTarget().minSpeedKph()).isNull();
        }
    }

    @Test
    void heartRateOnlyProfileGivesHeartRatePrimary() {
        TargetedWorkoutPrescription t = WorkoutIntensityTargetPolicy.apply(easyPrescription(), HR_ONLY);

        assertThat(t.targetAvailability()).isEqualTo(TargetAvailability.HEART_RATE_ONLY);
        for (TargetedWorkoutSegment s : t.segments()) {
            assertThat(s.primaryTargetType()).isEqualTo(PrimaryTargetType.HEART_RATE);
            assertThat(s.paceTarget()).isNull();
            assertThat(s.heartRateTarget()).isNotNull();
            assertThat(s.treadmillTarget().minSpeedKph()).isNull();
        }
    }

    @Test
    void paceOnlyProfileGivesPacePrimary() {
        TargetedWorkoutPrescription t = WorkoutIntensityTargetPolicy.apply(easyPrescription(), PACE_ONLY);

        assertThat(t.targetAvailability()).isEqualTo(TargetAvailability.PACE_ONLY);
        for (TargetedWorkoutSegment s : t.segments()) {
            assertThat(s.primaryTargetType()).isEqualTo(PrimaryTargetType.PACE);
            assertThat(s.paceTarget()).isNotNull();
            assertThat(s.heartRateTarget()).isNull();
            assertThat(s.treadmillTarget().minSpeedKph()).isNotNull();
        }
    }

    @Test
    void fullProfilePrefersPaceButIncludesHeartRateToo() {
        TargetedWorkoutPrescription t = WorkoutIntensityTargetPolicy.apply(easyPrescription(), FULL);

        assertThat(t.targetAvailability()).isEqualTo(TargetAvailability.FULL);
        for (TargetedWorkoutSegment s : t.segments()) {
            assertThat(s.primaryTargetType()).isEqualTo(PrimaryTargetType.PACE);
            assertThat(s.paceTarget()).isNotNull();
            assertThat(s.heartRateTarget()).isNotNull();
            assertThat(s.treadmillTarget().minSpeedKph()).isNotNull();
        }
    }

    @Test
    void warmUpAndMainGetDifferentTargetRanges() {
        TargetedWorkoutPrescription t = WorkoutIntensityTargetPolicy.apply(easyPrescription(), FULL);

        TargetedWorkoutSegment warmUp = t.segments().get(0);
        TargetedWorkoutSegment main = t.segments().get(1);
        assertThat(warmUp.intensityClass()).isEqualTo(IntensityClass.VERY_EASY);
        assertThat(main.intensityClass()).isEqualTo(IntensityClass.EASY);
        assertThat(warmUp.paceTarget()).isNotEqualTo(main.paceTarget());
        assertThat(warmUp.heartRateTarget()).isNotEqualTo(main.heartRateTarget());
    }

    @Test
    void restIsAlwaysNoneRegardlessOfProfile() {
        TargetedWorkoutPrescription t = WorkoutIntensityTargetPolicy.apply(restPrescription(), FULL);

        assertThat(t.targetAvailability()).isEqualTo(TargetAvailability.QUALITATIVE_ONLY);
        assertThat(t.segments()).singleElement().satisfies(s -> {
            assertThat(s.primaryTargetType()).isEqualTo(PrimaryTargetType.NONE);
            assertThat(s.paceTarget()).isNull();
            assertThat(s.heartRateTarget()).isNull();
            assertThat(s.treadmillTarget()).isNull();
        });
    }

    @Test
    void crossTrainingNeverUsesTheRunningThresholdProfile() {
        TargetedWorkoutPrescription t = WorkoutIntensityTargetPolicy.apply(crossTrainingPrescription(), FULL);

        assertThat(t.targetAvailability()).isEqualTo(TargetAvailability.QUALITATIVE_ONLY);
        for (TargetedWorkoutSegment s : t.segments()) {
            assertThat(s.primaryTargetType()).isEqualTo(PrimaryTargetType.QUALITATIVE);
            assertThat(s.paceTarget()).isNull();
            assertThat(s.heartRateTarget()).isNull();
            assertThat(s.treadmillTarget()).isNull();
        }
    }

    @Test
    void sameInputsGiveTheSameResult() {
        assertThat(WorkoutIntensityTargetPolicy.apply(easyPrescription(), FULL))
                .isEqualTo(WorkoutIntensityTargetPolicy.apply(easyPrescription(), FULL));
    }
}
