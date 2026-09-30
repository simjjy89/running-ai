package com.runningai.training;

import com.runningai.athlete.AthleteIntensityProfileResponse;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure mapping tests on hand-built prescriptions (no Spring context, no database, no
 * HTTP). All profile values are synthetic.
 */
class StructuredWorkoutMapperTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 30);
    private static final AthleteIntensityProfileResponse ABSENT = AthleteIntensityProfileResponse.empty();
    private static final AthleteIntensityProfileResponse FULL =
            new AthleteIntensityProfileResponse(true, 160, 300);

    private final StructuredWorkoutMapper mapper = new StructuredWorkoutMapper();

    private static WorkoutRecommendation dummyRecommendation(CandidateTrainingType intent) {
        return new WorkoutRecommendation(AS_OF, intent, 0, 999, IntensityClass.EASY,
                RecommendationConfidence.MEDIUM, DataSufficiency.MEDIUM, List.of(), "test", null);
    }

    private static WorkoutPrescription prescription(CandidateTrainingType intent, List<WorkoutSegment> segments, int total) {
        return new WorkoutPrescription(AS_OF, intent, total, segments, "test summary", dummyRecommendation(intent));
    }

    private static WorkoutSegment segment(SegmentType type, int minutes, IntensityClass intensity, String description) {
        return new WorkoutSegment(type, minutes, intensity, description);
    }

    @Test
    void basicMappingCopiesTopLevelFields() {
        WorkoutPrescription p = prescription(CandidateTrainingType.EASY, List.of(
                segment(SegmentType.WARM_UP, 10, IntensityClass.VERY_EASY, "warm-up"),
                segment(SegmentType.MAIN, 30, IntensityClass.EASY, "main"),
                segment(SegmentType.COOL_DOWN, 5, IntensityClass.VERY_EASY, "cool-down")), 45);
        TargetedWorkoutPrescription targeted = WorkoutIntensityTargetPolicy.apply(p, FULL);

        StructuredWorkout workout = mapper.map(targeted);

        assertThat(workout.asOfDate()).isEqualTo(targeted.asOfDate());
        assertThat(workout.intent()).isEqualTo(targeted.intent());
        assertThat(workout.totalDurationMinutes()).isEqualTo(targeted.totalDurationMinutes());
        assertThat(workout.steps()).hasSize(3);
    }

    @Test
    void stepOrderIsPreserved() {
        WorkoutPrescription p = prescription(CandidateTrainingType.LONG, List.of(
                segment(SegmentType.WARM_UP, 10, IntensityClass.VERY_EASY, "warm-up"),
                segment(SegmentType.MAIN, 70, IntensityClass.EASY, "main"),
                segment(SegmentType.COOL_DOWN, 10, IntensityClass.VERY_EASY, "cool-down")), 90);
        TargetedWorkoutPrescription targeted = WorkoutIntensityTargetPolicy.apply(p, FULL);

        StructuredWorkout workout = mapper.map(targeted);

        assertThat(workout.steps()).extracting(StructuredWorkoutStep::type)
                .containsExactly(SegmentType.WARM_UP, SegmentType.MAIN, SegmentType.COOL_DOWN);
        assertThat(workout.steps()).extracting(StructuredWorkoutStep::durationMinutes)
                .containsExactly(10, 70, 10);
    }

    @Test
    void mixedFiveSegmentWorkoutPreservesOrderAndPerSegmentTargets() {
        // warm-up / work / recovery / work / cool-down, hand-built directly as segments
        // (5B-1 never emits this shape, but the mapper must not assume the 3-segment case).
        WorkoutPrescription p = prescription(CandidateTrainingType.EASY, List.of(
                segment(SegmentType.WARM_UP, 5, IntensityClass.VERY_EASY, "warm-up"),
                segment(SegmentType.MAIN, 10, IntensityClass.EASY, "work 1"),
                segment(SegmentType.MAIN, 5, IntensityClass.VERY_EASY, "recovery 1"),
                segment(SegmentType.MAIN, 10, IntensityClass.EASY, "work 2"),
                segment(SegmentType.COOL_DOWN, 5, IntensityClass.VERY_EASY, "cool-down")), 35);
        TargetedWorkoutPrescription targeted = WorkoutIntensityTargetPolicy.apply(p, FULL);

        StructuredWorkout workout = mapper.map(targeted);

        assertThat(workout.steps()).hasSize(5);
        assertThat(workout.steps()).extracting(StructuredWorkoutStep::description)
                .containsExactly("warm-up", "work 1", "recovery 1", "work 2", "cool-down");
        assertThat(workout.steps()).extracting(StructuredWorkoutStep::durationMinutes)
                .containsExactly(5, 10, 5, 10, 5);
    }

    @Test
    void paceTargetIsPreservedLosslessly() {
        WorkoutPrescription p = prescription(CandidateTrainingType.EASY, List.of(
                segment(SegmentType.MAIN, 30, IntensityClass.EASY, "main")), 30);
        TargetedWorkoutPrescription targeted = WorkoutIntensityTargetPolicy.apply(p, FULL);

        StructuredWorkoutStep step = mapper.map(targeted).steps().get(0);

        assertThat(step.primaryTargetType()).isEqualTo(PrimaryTargetType.PACE);
        assertThat(step.paceTarget()).isEqualTo(targeted.segments().get(0).paceTarget());
        assertThat(step.paceTarget().fastSecondsPerKm()).isEqualTo(345);   // 300 * 1.15
        assertThat(step.paceTarget().slowSecondsPerKm()).isEqualTo(390);   // 300 * 1.30
    }

    @Test
    void heartRateTargetIsPreservedLosslesslyWhenPaceIsAbsent() {
        AthleteIntensityProfileResponse hrOnly = new AthleteIntensityProfileResponse(true, 160, null);
        WorkoutPrescription p = prescription(CandidateTrainingType.EASY, List.of(
                segment(SegmentType.MAIN, 30, IntensityClass.EASY, "main")), 30);
        TargetedWorkoutPrescription targeted = WorkoutIntensityTargetPolicy.apply(p, hrOnly);

        StructuredWorkoutStep step = mapper.map(targeted).steps().get(0);

        assertThat(step.primaryTargetType()).isEqualTo(PrimaryTargetType.HEART_RATE);
        assertThat(step.paceTarget()).isNull();
        assertThat(step.heartRateTarget()).isEqualTo(targeted.segments().get(0).heartRateTarget());
        assertThat(step.heartRateTarget().minBpm()).isEqualTo(120);   // round(160 * 0.75)
        assertThat(step.heartRateTarget().maxBpm()).isEqualTo(136);   // round(160 * 0.85)
    }

    @Test
    void treadmillNumericSemanticsArePreservedWithNoRenderedCueString() {
        WorkoutPrescription p = prescription(CandidateTrainingType.EASY, List.of(
                segment(SegmentType.MAIN, 30, IntensityClass.EASY, "main")), 30);
        TargetedWorkoutPrescription targeted = WorkoutIntensityTargetPolicy.apply(p, FULL);

        TreadmillTarget treadmill = mapper.map(targeted).steps().get(0).treadmillTarget();

        assertThat(treadmill).isNotNull();
        assertThat(treadmill.minSpeedKph()).isNotNull();
        assertThat(treadmill.maxSpeedKph()).isNotNull();
        assertThat(treadmill.minInclinePercent()).isEqualTo(0.5);
        assertThat(treadmill.maxInclinePercent()).isEqualTo(1.0);
        // StructuredWorkoutTarget stays numeric: no provider-rendered cue text anywhere
        // (the record's own field name legitimately contains "Incline", so check for the
        // rendered cue *tokens* instead, e.g. "Nkph"/"Npct", not the field name).
        assertThat(treadmill.toString()).doesNotContain("kph").doesNotContain("pct").doesNotContain("0.5-1");
    }

    @Test
    void restSegmentHasNoNumericTargetsAndNoException() {
        WorkoutPrescription p = prescription(CandidateTrainingType.REST,
                List.of(segment(SegmentType.REST, 0, IntensityClass.NONE, "rest")), 0);
        TargetedWorkoutPrescription targeted = WorkoutIntensityTargetPolicy.apply(p, FULL);

        StructuredWorkoutStep step = mapper.map(targeted).steps().get(0);

        assertThat(step.primaryTargetType()).isEqualTo(PrimaryTargetType.NONE);
        assertThat(step.paceTarget()).isNull();
        assertThat(step.heartRateTarget()).isNull();
        assertThat(step.treadmillTarget()).isNull();
    }

    @Test
    void crossTrainingNeverCarriesRunningThresholdTargetsEvenWithAFullProfile() {
        WorkoutPrescription p = prescription(CandidateTrainingType.CROSS_TRAINING, List.of(
                segment(SegmentType.WARM_UP, 5, IntensityClass.VERY_EASY, "warm-up"),
                segment(SegmentType.MAIN, 35, IntensityClass.EASY, "main"),
                segment(SegmentType.COOL_DOWN, 5, IntensityClass.VERY_EASY, "cool-down")), 45);
        TargetedWorkoutPrescription targeted = WorkoutIntensityTargetPolicy.apply(p, FULL);

        StructuredWorkout workout = mapper.map(targeted);

        for (StructuredWorkoutStep step : workout.steps()) {
            assertThat(step.primaryTargetType()).isEqualTo(PrimaryTargetType.QUALITATIVE);
            assertThat(step.paceTarget()).isNull();
            assertThat(step.heartRateTarget()).isNull();
            assertThat(step.treadmillTarget()).isNull();
        }
    }

    @Test
    void absentProfileMapsToQualitativeWithoutException() {
        WorkoutPrescription p = prescription(CandidateTrainingType.EASY, List.of(
                segment(SegmentType.MAIN, 30, IntensityClass.EASY, "main")), 30);
        TargetedWorkoutPrescription targeted = WorkoutIntensityTargetPolicy.apply(p, ABSENT);

        StructuredWorkoutStep step = mapper.map(targeted).steps().get(0);

        assertThat(step.primaryTargetType()).isEqualTo(PrimaryTargetType.QUALITATIVE);
        assertThat(step.paceTarget()).isNull();
        assertThat(step.heartRateTarget()).isNull();
        assertThat(step.treadmillTarget()).isNotNull();   // incline is still an operational default
        assertThat(step.treadmillTarget().minSpeedKph()).isNull();
    }

    @Test
    void noProviderSyntaxLeaksIntoTheStructuredWorkout() {
        WorkoutPrescription p = prescription(CandidateTrainingType.EASY, List.of(
                segment(SegmentType.WARM_UP, 10, IntensityClass.VERY_EASY, "warm-up"),
                segment(SegmentType.MAIN, 30, IntensityClass.EASY, "main"),
                segment(SegmentType.COOL_DOWN, 5, IntensityClass.VERY_EASY, "cool-down")), 45);
        TargetedWorkoutPrescription targeted = WorkoutIntensityTargetPolicy.apply(p, FULL);

        StructuredWorkout workout = mapper.map(targeted);

        // Field names like "minInclinePercent" legitimately contain "Incline" -- that is a
        // RunningAI canonical-unit name, not rendered provider syntax. What must never appear
        // is an actual rendered token/value: "hr=1s", "Nkph", "N-Mpct", an Intervals Workout
        // Builder pace token like "@4:50", or the words "Intervals"/"Garmin" themselves.
        String rendered = workout.toString();
        for (String forbidden : new String[]{"Intervals", "Garmin", "hr=1s", "kph", "pct", "@4:50"}) {
            assertThat(rendered).doesNotContain(forbidden);
        }
    }

    @Test
    void sameInputGivesTheSameResult() {
        WorkoutPrescription p = prescription(CandidateTrainingType.EASY, List.of(
                segment(SegmentType.MAIN, 30, IntensityClass.EASY, "main")), 30);
        TargetedWorkoutPrescription targeted = WorkoutIntensityTargetPolicy.apply(p, FULL);

        assertThat(mapper.map(targeted)).isEqualTo(mapper.map(targeted));
    }
}
