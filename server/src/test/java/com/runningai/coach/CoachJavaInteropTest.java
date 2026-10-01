package com.runningai.coach;

import com.runningai.training.IntensityClass;
import com.runningai.training.SegmentType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The other direction of the interop guarantee: existing <em>Java</em> code can construct and read
 * the new Kotlin coach types without a shim. Written in Java on purpose — a Kotlin test could not
 * prove this.
 */
class CoachJavaInteropTest {

    @Test
    void javaCanConstructAndReadKotlinCoachTypes() {
        WorkoutDraftSegment segment = new WorkoutDraftSegment(
                SegmentType.MAIN, 30, IntensityClass.EASY, "Steady running",
                330, 360, 140, 155, null, null, null, null, null, null);

        CoachAssessment assessment = new CoachAssessment(
                "Recovery unknown", "Load steady", "EASY", "Keep it easy today.", List.of());

        WorkoutDraft draft = new WorkoutDraft(
                null, null, 1, LocalDate.of(2026, 10, 2), "Easy Run", "EASY", 30,
                assessment, List.of(segment), CoachProvider.CLAUDE, "test-model",
                WorkoutDraftStatus.DRAFT, null);

        assertThat(draft.getTitle()).isEqualTo("Easy Run");
        assertThat(draft.getVersion()).isEqualTo(1);
        assertThat(draft.getProvider()).isEqualTo(CoachProvider.CLAUDE);
        assertThat(draft.getStatus()).isEqualTo(WorkoutDraftStatus.DRAFT);
        assertThat(draft.getSegments()).hasSize(1);
        assertThat(draft.getSegments().get(0).getType()).isEqualTo(SegmentType.MAIN);
        assertThat(draft.getSegments().get(0).getEffectiveDurationMinutes()).isEqualTo(30);
        assertThat(draft.getAssessment().getRationale()).isEqualTo("Keep it easy today.");
    }

    @Test
    void javaCanImplementTheKotlinAiCoachInterface() {
        AiCoach coach = new AiCoach() {
            @Override
            public WorkoutDraft createWorkout(TrainingContext context) {
                return stub(context.getDate(), 1);
            }

            @Override
            public WorkoutDraft reviseWorkout(TrainingContext context, WorkoutDraft currentDraft, String userRequest) {
                return stub(context.getDate(), currentDraft.getVersion() + 1);
            }

            private WorkoutDraft stub(LocalDate date, int version) {
                return new WorkoutDraft(null, null, version, date, "Stub", "EASY", 20,
                        new CoachAssessment("a", "b", "EASY", "c", List.of()),
                        List.of(new WorkoutDraftSegment(SegmentType.MAIN, 20, IntensityClass.EASY,
                                null, null, null, null, null, null, null, null, null, null, null)),
                        CoachProvider.CLAUDE, null, WorkoutDraftStatus.DRAFT, null);
            }
        };

        TrainingContext context = new TrainingContext(
                LocalDate.of(2026, 10, 2),
                new AthleteThresholds(170, 300),
                new RecentTraining(List.of(), null, null, null, null, false, null, null, 0, 1, List.of()),
                new RecoveryContext(null, null, null, null, null),
                new WeeklyContext(0, 0, null, 0, 0, null, 0, 0, 0, null, null, 0, 7, "UNKNOWN"),
                new SessionConstraints(null, null, null, null, null));

        WorkoutDraft created = coach.createWorkout(context);
        WorkoutDraft revised = coach.reviseWorkout(context, created, "make it shorter");

        assertThat(created.getVersion()).isEqualTo(1);
        assertThat(revised.getVersion()).isEqualTo(2);
    }

    @Test
    void javaSeesKotlinNullableFieldsAsNull() {
        RecoveryContext empty = new RecoveryContext(null, null, null, null, null);

        assertThat(empty.getHrvMs()).isNull();
        assertThat(empty.getSleepHours()).isNull();
        assertThat(empty.getAnyAvailable()).isFalse();
    }
}
