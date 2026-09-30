package com.runningai.training;

import com.runningai.common.exception.UnprocessableRequestException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static com.runningai.training.CandidateTrainingType.CROSS_TRAINING;
import static com.runningai.training.CandidateTrainingType.EASY;
import static com.runningai.training.CandidateTrainingType.LONG;
import static com.runningai.training.CandidateTrainingType.QUALITY;
import static com.runningai.training.CandidateTrainingType.RECOVERY;
import static com.runningai.training.CandidateTrainingType.REST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure structure rules on hand-built recommendations (no database); the policy does not read the nested context. */
class WorkoutPrescriptionPolicyTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 30);
    private static final List<CandidateTrainingType> SUPPORTED = List.of(REST, RECOVERY, EASY, LONG, CROSS_TRAINING);

    /** The default 5A ranges per intent. */
    private static WorkoutRecommendation rec(CandidateTrainingType intent) {
        return rec(intent, WorkoutRecommendationPolicy.minMinutes(intent), WorkoutRecommendationPolicy.maxMinutes(intent));
    }

    private static WorkoutRecommendation rec(CandidateTrainingType intent, int min, int max) {
        return new WorkoutRecommendation(AS_OF, intent, min, max, WorkoutRecommendationPolicy.intensity(intent),
                RecommendationConfidence.MEDIUM, DataSufficiency.HIGH, List.of(), "source summary", null);
    }

    private static List<Object> shape(WorkoutPrescription p) {
        return p.segments().stream().map(s -> (Object) (s.type() + ":" + s.durationMinutes() + ":" + s.intensityClass())).toList();
    }

    // ---- default structures ------------------------------------------------------------------

    @Test
    void restIsOneZeroMinuteRestSegment() {
        WorkoutPrescription p = WorkoutPrescriptionPolicy.prescribe(rec(REST));

        assertThat(p.intent()).isEqualTo(REST);
        assertThat(p.totalDurationMinutes()).isZero();
        assertThat(p.segments()).hasSize(1);
        assertThat(p.segments().get(0).type()).isEqualTo(SegmentType.REST);
        assertThat(p.segments().get(0).durationMinutes()).isZero();
        assertThat(p.segments().get(0).intensityClass()).isEqualTo(IntensityClass.NONE);
        assertThat(p.summary()).isEqualTo("Rest day: no training is prescribed.");
    }

    @Test
    void recoveryIsThirtyMinutesVeryEasy() {
        WorkoutPrescription p = WorkoutPrescriptionPolicy.prescribe(rec(RECOVERY));

        assertThat(p.totalDurationMinutes()).isEqualTo(30);
        assertThat(shape(p)).containsExactly("WARM_UP:5:VERY_EASY", "MAIN:20:VERY_EASY", "COOL_DOWN:5:VERY_EASY");
        assertThat(p.summary()).isEqualTo("30-minute recovery session with warm-up, steady main work, and cool-down.");
    }

    @Test
    void easyIsFortyFiveMinutes() {
        WorkoutPrescription p = WorkoutPrescriptionPolicy.prescribe(rec(EASY));

        assertThat(p.totalDurationMinutes()).isEqualTo(45);
        assertThat(shape(p)).containsExactly("WARM_UP:10:VERY_EASY", "MAIN:30:EASY", "COOL_DOWN:5:VERY_EASY");
        assertThat(p.summary()).isEqualTo("45-minute easy session with warm-up, steady main work, and cool-down.");
        assertThat(p.segments().get(0).description()).isEqualTo("Easy warm-up");
        assertThat(p.segments().get(1).description()).isEqualTo("Steady easy running");
        assertThat(p.segments().get(2).description()).isEqualTo("Easy cool-down");
    }

    @Test
    void longIsNinetyMinutes() {
        WorkoutPrescription p = WorkoutPrescriptionPolicy.prescribe(rec(LONG));

        assertThat(p.totalDurationMinutes()).isEqualTo(90);
        assertThat(shape(p)).containsExactly("WARM_UP:10:VERY_EASY", "MAIN:70:EASY", "COOL_DOWN:10:VERY_EASY");
    }

    @Test
    void crossTrainingIsFortyFiveMinutesWithoutChoosingAModality() {
        WorkoutPrescription p = WorkoutPrescriptionPolicy.prescribe(rec(CROSS_TRAINING));

        assertThat(p.totalDurationMinutes()).isEqualTo(45);
        assertThat(shape(p)).containsExactly("WARM_UP:5:VERY_EASY", "MAIN:35:EASY", "COOL_DOWN:5:VERY_EASY");
        assertThat(p.segments().get(1).description().toLowerCase()).contains("modality not specified")
                .doesNotContain("bike", "elliptical", "stairs");
    }

    // ---- QUALITY -----------------------------------------------------------------------------------

    @Test
    void qualityIsExplicitlyUnsupported() {
        assertThatThrownBy(() -> WorkoutPrescriptionPolicy.prescribe(rec(QUALITY)))
                .isInstanceOf(UnprocessableRequestException.class)
                .satisfies(e -> assertThat(((UnprocessableRequestException) e).getCode())
                        .isEqualTo("QUALITY_PRESCRIPTION_NOT_SUPPORTED"));
    }

    // ---- range clamp ---------------------------------------------------------------------------------

    @Test
    void narrowRangeClampsThePreferredDurationAndSegmentsFollow() {
        WorkoutPrescription p = WorkoutPrescriptionPolicy.prescribe(rec(EASY, 30, 35));

        assertThat(p.totalDurationMinutes()).isEqualTo(35);
        assertThat(p.segments().stream().mapToInt(WorkoutSegment::durationMinutes).sum()).isEqualTo(35);
        assertThat(shape(p)).containsExactly("WARM_UP:5:VERY_EASY", "MAIN:25:EASY", "COOL_DOWN:5:VERY_EASY");
    }

    @Test
    void rangeAbovePreferredRaisesToTheMinimum() {
        WorkoutPrescription p = WorkoutPrescriptionPolicy.prescribe(rec(RECOVERY, 40, 60));

        assertThat(p.totalDurationMinutes()).isEqualTo(40);
        assertThat(p.segments().stream().mapToInt(WorkoutSegment::durationMinutes).sum()).isEqualTo(40);
    }

    @Test
    void wideRangeKeepsThePreferredDuration() {
        WorkoutPrescription p = WorkoutPrescriptionPolicy.prescribe(rec(EASY, 30, 90));

        assertThat(p.totalDurationMinutes()).isEqualTo(45);
        assertThat(shape(p)).containsExactly("WARM_UP:10:VERY_EASY", "MAIN:30:EASY", "COOL_DOWN:5:VERY_EASY");
    }

    @Test
    void invalidRangesAreInvariantViolationsNotSilentlyCorrected() {
        assertThatThrownBy(() -> WorkoutPrescriptionPolicy.prescribe(rec(EASY, 60, 30))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> WorkoutPrescriptionPolicy.prescribe(rec(EASY, -5, 30))).isInstanceOf(IllegalStateException.class);
        // a non-REST intent whose range allows only 0 minutes cannot have a positive MAIN
        assertThatThrownBy(() -> WorkoutPrescriptionPolicy.prescribe(rec(EASY, 0, 0))).isInstanceOf(IllegalStateException.class);
        // REST must be exactly 0 minutes
        assertThatThrownBy(() -> WorkoutPrescriptionPolicy.prescribe(rec(REST, 10, 20))).isInstanceOf(IllegalStateException.class);
    }

    // ---- invariants over many ranges --------------------------------------------------------------------

    @Test
    void invariantsHoldForEverySupportedIntentAndManyRanges() {
        int checked = 0;
        for (CandidateTrainingType intent : SUPPORTED) {
            if (intent == REST) {
                continue;
            }
            for (int min = 1; min <= 130; min++) {
                for (int max = min; max <= 130; max += 3) {
                    WorkoutRecommendation r = rec(intent, min, max);
                    WorkoutPrescription p = WorkoutPrescriptionPolicy.prescribe(r);

                    assertThat(p.intent()).isEqualTo(intent);
                    assertThat(p.totalDurationMinutes()).isBetween(min, max);
                    assertThat(p.segments().stream().mapToInt(WorkoutSegment::durationMinutes).sum()).isEqualTo(p.totalDurationMinutes());
                    assertThat(p.segments()).allSatisfy(s -> assertThat(s.durationMinutes()).isNotNegative());
                    assertThat(p.segments().stream().filter(s -> s.type() == SegmentType.MAIN).findFirst().orElseThrow().durationMinutes())
                            .isPositive();
                    assertThat(p.segments()).extracting(WorkoutSegment::type)
                            .containsExactly(SegmentType.WARM_UP, SegmentType.MAIN, SegmentType.COOL_DOWN);
                    assertThat(p.recommendation()).isSameAs(r);
                    checked++;
                }
            }
        }
        assertThat(checked).isGreaterThan(10_000);
    }

    @Test
    void everySupportedIntentDefaultStaysInsideItsFiveARange() {
        for (CandidateTrainingType intent : SUPPORTED) {
            WorkoutRecommendation r = rec(intent);
            WorkoutPrescription p = WorkoutPrescriptionPolicy.prescribe(r);

            assertThat(p.intent()).isEqualTo(r.recommendedIntent());
            assertThat(p.totalDurationMinutes()).isBetween(r.durationMinMinutes(), r.durationMaxMinutes());
            assertThat(p.asOfDate()).isEqualTo(r.asOfDate());
        }
    }

    @Test
    void intensityMappingIsQualitativeOnly() {
        assertThat(WorkoutPrescriptionPolicy.prescribe(rec(RECOVERY)).segments()).extracting(WorkoutSegment::intensityClass)
                .containsOnly(IntensityClass.VERY_EASY);
        for (CandidateTrainingType intent : List.of(EASY, LONG, CROSS_TRAINING)) {
            assertThat(WorkoutPrescriptionPolicy.prescribe(rec(intent)).segments()).extracting(WorkoutSegment::intensityClass)
                    .containsExactly(IntensityClass.VERY_EASY, IntensityClass.EASY, IntensityClass.VERY_EASY);
        }
    }

    @Test
    void sameRecommendationGivesTheSamePrescription() {
        for (CandidateTrainingType intent : SUPPORTED) {
            assertThat(WorkoutPrescriptionPolicy.prescribe(rec(intent))).isEqualTo(WorkoutPrescriptionPolicy.prescribe(rec(intent)));
        }
    }
}
