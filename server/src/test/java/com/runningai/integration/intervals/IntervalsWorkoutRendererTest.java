package com.runningai.integration.intervals;

import com.runningai.training.CandidateTrainingType;
import com.runningai.training.HeartRateTarget;
import com.runningai.training.IntensityClass;
import com.runningai.training.PaceTarget;
import com.runningai.training.PrimaryTargetType;
import com.runningai.training.SegmentType;
import com.runningai.training.StructuredWorkout;
import com.runningai.training.StructuredWorkoutMapper;
import com.runningai.training.TargetAvailability;
import com.runningai.training.TargetedWorkoutPrescription;
import com.runningai.training.TargetedWorkoutSegment;
import com.runningai.training.TreadmillTarget;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden-output tests: {@link StructuredWorkout} (via {@link StructuredWorkoutMapper}) ->
 * exact rendered Intervals.icu text. Segments are hand-built directly with precomputed
 * synthetic pace/HR/treadmill values (threshold pace 300s/km, LTHR 160bpm -- the same
 * synthetic profile used throughout Phase 5B-2's tests) rather than routed through the
 * training package's package-private policy classes, so this stays a pure, package-local
 * unit test of the renderer only.
 */
class IntervalsWorkoutRendererTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 10, 1);

    // Precomputed from threshold pace=300s/km, LTHR=160bpm (RunningIntensityTargetPolicy, Phase 5B-2).
    private static final PaceTarget VERY_EASY_PACE = new PaceTarget(375, 435);
    private static final PaceTarget EASY_PACE = new PaceTarget(345, 390);
    private static final HeartRateTarget VERY_EASY_HR = new HeartRateTarget(65, 78, 104, 125);
    private static final HeartRateTarget EASY_HR = new HeartRateTarget(75, 85, 120, 136);
    private static final TreadmillTarget VERY_EASY_TREADMILL_FULL = new TreadmillTarget(8.3, 9.6, 0.0, 0.5);
    private static final TreadmillTarget EASY_TREADMILL_FULL = new TreadmillTarget(9.2, 10.4, 0.5, 1.0);
    private static final TreadmillTarget VERY_EASY_TREADMILL_INCLINE_ONLY = new TreadmillTarget(null, null, 0.0, 0.5);
    private static final TreadmillTarget EASY_TREADMILL_INCLINE_ONLY = new TreadmillTarget(null, null, 0.5, 1.0);

    private final StructuredWorkoutMapper mapper = new StructuredWorkoutMapper();
    private final IntervalsWorkoutRenderer renderer = new IntervalsWorkoutRenderer();

    private static TargetedWorkoutSegment segment(SegmentType type, int minutes, IntensityClass intensity,
                                                    String description, PrimaryTargetType primary,
                                                    PaceTarget pace, HeartRateTarget hr, TreadmillTarget treadmill) {
        return new TargetedWorkoutSegment(type, minutes, intensity, description, primary, pace, hr, treadmill);
    }

    private static TargetedWorkoutPrescription prescription(CandidateTrainingType intent, int total,
                                                              List<TargetedWorkoutSegment> segments) {
        return new TargetedWorkoutPrescription(AS_OF, intent, total, segments, TargetAvailability.FULL, null, null);
    }

    private String render(CandidateTrainingType intent, int total, List<TargetedWorkoutSegment> segments) {
        StructuredWorkout workout = mapper.map(prescription(intent, total, segments));
        return renderer.render(workout).workoutText();
    }

    // ---- A. Easy pace run (full profile) -----------------------------------------------

    @Test
    void goldenEasyPaceRun() {
        String text = render(CandidateTrainingType.EASY, 45, List.of(
                segment(SegmentType.WARM_UP, 10, IntensityClass.VERY_EASY, "warm-up", PrimaryTargetType.PACE,
                        VERY_EASY_PACE, VERY_EASY_HR, VERY_EASY_TREADMILL_FULL),
                segment(SegmentType.MAIN, 30, IntensityClass.EASY, "main", PrimaryTargetType.PACE,
                        EASY_PACE, EASY_HR, EASY_TREADMILL_FULL),
                segment(SegmentType.COOL_DOWN, 5, IntensityClass.VERY_EASY, "cool-down", PrimaryTargetType.PACE,
                        VERY_EASY_PACE, VERY_EASY_HR, VERY_EASY_TREADMILL_FULL)));

        assertThat(text).isEqualTo(
                "- Warm Up 8.3-9.6kph Incline0-0.5pct 10m 6:15-7:15/km Pace\n"
                        + "- Main 9.2-10.4kph Incline0.5-1pct 30m 5:45-6:30/km Pace\n"
                        + "- Cool Down 8.3-9.6kph Incline0-0.5pct 5m 6:15-7:15/km Pace");
    }

    // ---- B. Mixed 5-segment (warmup/work/recovery/work/cooldown) -----------------------

    @Test
    void goldenFiveSegmentMixedWorkout() {
        String text = render(CandidateTrainingType.EASY, 35, List.of(
                segment(SegmentType.WARM_UP, 5, IntensityClass.VERY_EASY, "warm-up", PrimaryTargetType.PACE,
                        VERY_EASY_PACE, VERY_EASY_HR, VERY_EASY_TREADMILL_FULL),
                segment(SegmentType.MAIN, 10, IntensityClass.EASY, "work 1", PrimaryTargetType.PACE,
                        EASY_PACE, EASY_HR, EASY_TREADMILL_FULL),
                segment(SegmentType.MAIN, 5, IntensityClass.VERY_EASY, "recovery 1", PrimaryTargetType.PACE,
                        VERY_EASY_PACE, VERY_EASY_HR, VERY_EASY_TREADMILL_FULL),
                segment(SegmentType.MAIN, 10, IntensityClass.EASY, "work 2", PrimaryTargetType.PACE,
                        EASY_PACE, EASY_HR, EASY_TREADMILL_FULL),
                segment(SegmentType.COOL_DOWN, 5, IntensityClass.VERY_EASY, "cool-down", PrimaryTargetType.PACE,
                        VERY_EASY_PACE, VERY_EASY_HR, VERY_EASY_TREADMILL_FULL)));

        assertThat(text).isEqualTo(
                "- Warm Up 8.3-9.6kph Incline0-0.5pct 5m 6:15-7:15/km Pace\n"
                        + "- Main 9.2-10.4kph Incline0.5-1pct 10m 5:45-6:30/km Pace\n"
                        + "- Main 8.3-9.6kph Incline0-0.5pct 5m 6:15-7:15/km Pace\n"
                        + "- Main 9.2-10.4kph Incline0.5-1pct 10m 5:45-6:30/km Pace\n"
                        + "- Cool Down 8.3-9.6kph Incline0-0.5pct 5m 6:15-7:15/km Pace");
    }

    // ---- C. HR-only (%LTHR + hr=1s, no threshold pace) ---------------------------------

    @Test
    void goldenHeartRateOnlyWorkout() {
        String text = render(CandidateTrainingType.EASY, 45, List.of(
                segment(SegmentType.WARM_UP, 10, IntensityClass.VERY_EASY, "warm-up", PrimaryTargetType.HEART_RATE,
                        null, VERY_EASY_HR, VERY_EASY_TREADMILL_INCLINE_ONLY),
                segment(SegmentType.MAIN, 30, IntensityClass.EASY, "main", PrimaryTargetType.HEART_RATE,
                        null, EASY_HR, EASY_TREADMILL_INCLINE_ONLY),
                segment(SegmentType.COOL_DOWN, 5, IntensityClass.VERY_EASY, "cool-down", PrimaryTargetType.HEART_RATE,
                        null, VERY_EASY_HR, VERY_EASY_TREADMILL_INCLINE_ONLY)));

        assertThat(text).isEqualTo(
                "- Warm Up Incline0-0.5pct 10m 65-78% LTHR hr=1s\n"
                        + "- Main Incline0.5-1pct 30m 75-85% LTHR hr=1s\n"
                        + "- Cool Down Incline0-0.5pct 5m 65-78% LTHR hr=1s");
    }

    // ---- D. Profile absent: qualitative fallback, incline still present ----------------

    @Test
    void goldenQualitativeFallbackWorkout() {
        String text = render(CandidateTrainingType.EASY, 45, List.of(
                segment(SegmentType.WARM_UP, 10, IntensityClass.VERY_EASY, "warm-up", PrimaryTargetType.QUALITATIVE,
                        null, null, VERY_EASY_TREADMILL_INCLINE_ONLY),
                segment(SegmentType.MAIN, 30, IntensityClass.EASY, "main", PrimaryTargetType.QUALITATIVE,
                        null, null, EASY_TREADMILL_INCLINE_ONLY),
                segment(SegmentType.COOL_DOWN, 5, IntensityClass.VERY_EASY, "cool-down", PrimaryTargetType.QUALITATIVE,
                        null, null, VERY_EASY_TREADMILL_INCLINE_ONLY)));

        assertThat(text).isEqualTo(
                "- Warm Up Incline0-0.5pct 10m\n"
                        + "- Main Incline0.5-1pct 30m\n"
                        + "- Cool Down Incline0-0.5pct 5m");
    }

    // ---- E. REST: no structured content at all -----------------------------------------

    @Test
    void restRendersNoStructuredContent() {
        String text = render(CandidateTrainingType.REST, 0,
                List.of(segment(SegmentType.REST, 0, IntensityClass.NONE, "rest", PrimaryTargetType.NONE,
                        null, null, null)));

        assertThat(text).isEmpty();
    }

    // ---- F. Heterogeneous: pace / HR / qualitative in different steps of one workout ---

    @Test
    void goldenMixedTargetTypesAcrossSteps() {
        String text = render(CandidateTrainingType.EASY, 40, List.of(
                segment(SegmentType.WARM_UP, 10, IntensityClass.VERY_EASY, "warm-up", PrimaryTargetType.PACE,
                        new PaceTarget(300, 330), null, new TreadmillTarget(10.0, 11.0, 0.5, 1.0)),
                segment(SegmentType.MAIN, 20, IntensityClass.EASY, "main", PrimaryTargetType.HEART_RATE,
                        null, new HeartRateTarget(65, 75, 130, 142), new TreadmillTarget(null, null, 0.0, 0.5)),
                segment(SegmentType.COOL_DOWN, 10, IntensityClass.VERY_EASY, "cool-down", PrimaryTargetType.QUALITATIVE,
                        null, null, null)));

        assertThat(text).isEqualTo(
                "- Warm Up 10.0-11.0kph Incline0.5-1pct 10m 5:00-5:30/km Pace\n"
                        + "- Main Incline0-0.5pct 20m 65-75% LTHR hr=1s\n"
                        + "- Cool Down 10m");
    }

    // ---- Cue ordering regression (must never regress: this is why the cue exists) ------

    @Test
    void rendersGarminCueBeforeDurationAndTarget() {
        String line = render(CandidateTrainingType.EASY, 30, List.of(
                segment(SegmentType.MAIN, 30, IntensityClass.EASY, "main", PrimaryTargetType.PACE,
                        EASY_PACE, EASY_HR, EASY_TREADMILL_FULL)));

        int cueIndex = line.indexOf("Incline0.5-1pct");
        int durationIndex = line.indexOf("30m");
        int targetIndex = line.indexOf("5:45-6:30/km Pace");

        assertThat(cueIndex).isGreaterThanOrEqualTo(0);
        assertThat(cueIndex).isLessThan(durationIndex);
        assertThat(durationIndex).isLessThan(targetIndex);
    }

    // ---- Determinism, locale and newline policy -----------------------------------------

    @Test
    void sameInputGivesTheSameOutput() {
        List<TargetedWorkoutSegment> segments = List.of(
                segment(SegmentType.MAIN, 30, IntensityClass.EASY, "main", PrimaryTargetType.PACE,
                        EASY_PACE, EASY_HR, EASY_TREADMILL_FULL));

        assertThat(render(CandidateTrainingType.EASY, 30, segments))
                .isEqualTo(render(CandidateTrainingType.EASY, 30, segments));
    }

    @Test
    void renderingIsLocaleIndependent() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);   // uses ',' as the decimal separator by default
            String text = render(CandidateTrainingType.EASY, 30, List.of(
                    segment(SegmentType.MAIN, 30, IntensityClass.EASY, "main", PrimaryTargetType.PACE,
                            EASY_PACE, EASY_HR, EASY_TREADMILL_FULL)));

            assertThat(text).contains("9.2-10.4kph");
            assertThat(text).doesNotContain(",");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void usesCanonicalNewlinesOnly() {
        String text = render(CandidateTrainingType.EASY, 45, List.of(
                segment(SegmentType.WARM_UP, 10, IntensityClass.VERY_EASY, "warm-up", PrimaryTargetType.PACE,
                        VERY_EASY_PACE, VERY_EASY_HR, VERY_EASY_TREADMILL_FULL),
                segment(SegmentType.MAIN, 30, IntensityClass.EASY, "main", PrimaryTargetType.PACE,
                        EASY_PACE, EASY_HR, EASY_TREADMILL_FULL),
                segment(SegmentType.COOL_DOWN, 5, IntensityClass.VERY_EASY, "cool-down", PrimaryTargetType.PACE,
                        VERY_EASY_PACE, VERY_EASY_HR, VERY_EASY_TREADMILL_FULL)));

        assertThat(text).doesNotContain("\r");
        assertThat(text.split("\n")).hasSize(3);
    }

    @Test
    void rendererDoesNotRecomputeAnyTrainingValue() {
        // Sanity check that the renderer is a pure text transform: passing an already
        // "wrong" (but internally consistent) PaceTarget must render that value verbatim,
        // never silently "fixed" against threshold pace or any other domain rule.
        PaceTarget unusual = new PaceTarget(200, 210);
        String text = render(CandidateTrainingType.EASY, 10,
                List.of(segment(SegmentType.MAIN, 10, IntensityClass.EASY, "main", PrimaryTargetType.PACE,
                        unusual, null, null)));

        assertThat(text).isEqualTo("- Main 10m 3:20-3:30/km Pace");
    }
}
