package com.runningai.integration.intervals;

import com.runningai.training.CandidateTrainingType;
import com.runningai.training.HeartRateTarget;
import com.runningai.training.PaceTarget;
import com.runningai.training.PrimaryTargetType;
import com.runningai.training.SegmentType;
import com.runningai.training.StructuredWorkout;
import com.runningai.training.StructuredWorkoutStep;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Renders a provider-neutral {@link StructuredWorkout} into Intervals.icu Workout
 * Builder text (render only: no HTTP, no publish, no idempotency marker -- see a future
 * publisher phase for those). Deterministic and locale-independent: the same input
 * always produces the same output, decimals always use {@code .}, and lines are joined
 * with {@code \n} regardless of the host OS.
 * <p>
 * Each rendered line follows the legacy renderer's step order, confirmed by real
 * Forerunner 265 observation to be the only ordering where the Garmin-safe cue actually
 * reaches the watch display (text placed after a step's duration/target token does not):
 * <pre>{@code label -> cue -> duration -> target}</pre>
 * Which target (if any) is rendered follows the step's own {@link PrimaryTargetType}
 * exactly; this renderer does not recompute, rank, or override that choice. A
 * {@link CandidateTrainingType#REST} workout renders no structured content at all,
 * matching the legacy renderer's "REST never becomes a structured Run" rule.
 * <p>
 * Known, still-open items carried over from Phase 5C-0 (not resolved by rendering
 * correctly): a real Garmin device has been observed to show "no target" for some pace
 * steps despite Intervals.icu accepting the identical text (root cause unconfirmed), and
 * the {@code %LTHR}/{@code hr=1s} heart-rate form has only ever been confirmed to survive
 * an Intervals.icu server readback, never confirmed against an actual device display.
 */
@Component
public class IntervalsWorkoutRenderer {

    public RenderedIntervalsWorkout render(StructuredWorkout workout) {
        if (workout.intent() == CandidateTrainingType.REST) {
            return new RenderedIntervalsWorkout("");
        }
        String text = workout.steps().stream()
                .map(step -> "- " + renderStepLine(step))
                .collect(Collectors.joining("\n"));
        return new RenderedIntervalsWorkout(text);
    }

    private static String renderStepLine(StructuredWorkoutStep step) {
        String label = label(step.type());
        String cue = GarminSafeCueFormatter.cue(step.treadmillTarget());
        String duration = durationToken(step.durationMinutes());
        String target = renderTarget(step);
        return Stream.of(label, cue, duration, target)
                .filter(token -> token != null && !token.isBlank())
                .collect(Collectors.joining(" "));
    }

    private static String renderTarget(StructuredWorkoutStep step) {
        return switch (step.primaryTargetType()) {
            case PACE -> renderPace(step.paceTarget());
            case HEART_RATE -> renderHeartRate(step.heartRateTarget());
            case QUALITATIVE, NONE -> null;
        };
    }

    private static String label(SegmentType type) {
        return switch (type) {
            case WARM_UP -> "Warm Up";
            case MAIN -> "Main";
            case COOL_DOWN -> "Cool Down";
            case REST -> "Rest";
        };
    }

    /** "M:SS/km Pace" (single) or "M:SS-M:SS/km Pace" (range); the domain already guarantees fast <= slow. */
    private static String renderPace(PaceTarget pace) {
        String fast = paceToken(pace.fastSecondsPerKm());
        String slow = paceToken(pace.slowSecondsPerKm());
        if (pace.fastSecondsPerKm() == pace.slowSecondsPerKm()) {
            return fast + " Pace";
        }
        String fastWithoutUnit = fast.substring(0, fast.length() - "/km".length());
        return fastWithoutUnit + "-" + slow + " Pace";
    }

    private static String paceToken(int secondsPerKm) {
        if (secondsPerKm <= 0 || secondsPerKm >= 3600) {
            throw new IllegalArgumentException("pace seconds/km out of range: " + secondsPerKm);
        }
        int minutes = secondsPerKm / 60;
        int seconds = secondsPerKm % 60;
        return minutes + ":" + String.format(Locale.ROOT, "%02d", seconds) + "/km";
    }

    /**
     * "N% LTHR hr=1s" (single) or "N-M% LTHR hr=1s" (range). The percentages are rendered
     * exactly as {@link HeartRateTarget} already carries them (whole percent, Phase 5B-2);
     * this renderer never recomputes a percentage from bpm/LTHR, and never substitutes an
     * absolute-bpm target -- legacy evidence has no confirmed Intervals.icu token for that
     * (Phase 5C-0, {@code DECISION-HEART-RATE-STRUCTURED-TARGET-NOT-IMPLEMENTED.md}).
     */
    private static String renderHeartRate(HeartRateTarget hr) {
        String range = hr.minPercentLthr() == hr.maxPercentLthr()
                ? String.valueOf(hr.maxPercentLthr())
                : hr.minPercentLthr() + "-" + hr.maxPercentLthr();
        return range + "% LTHR hr=1s";
    }

    /** "XhYmZs", each component omitted when zero; never called with a non-positive duration. */
    private static String durationToken(int minutes) {
        if (minutes <= 0) {
            throw new IllegalStateException("A structured step must have a positive duration to render: " + minutes);
        }
        long totalSeconds = Math.round(minutes * 60.0);
        long hours = totalSeconds / 3600;
        long remainder = totalSeconds % 3600;
        long mins = remainder / 60;
        long secs = remainder % 60;
        StringBuilder text = new StringBuilder();
        if (hours > 0) {
            text.append(hours).append('h');
        }
        if (mins > 0) {
            text.append(mins).append('m');
        }
        if (secs > 0) {
            text.append(secs).append('s');
        }
        return text.toString();
    }
}
