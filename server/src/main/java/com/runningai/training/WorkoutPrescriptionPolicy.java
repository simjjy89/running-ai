package com.runningai.training;

import com.runningai.common.exception.UnprocessableRequestException;

import java.util.List;

/**
 * Turns a {@link WorkoutRecommendation} into an exact-duration warm-up / main / cool-down structure.
 * The intent is taken from the recommendation unchanged; only the total duration and the split are
 * decided here.
 * <p>
 * Total duration = a per-intent preferred default clamped to the recommendation's
 * [durationMinMinutes, durationMaxMinutes]; the defaults are deterministic scheduling values, not
 * physiological optima. Segments are whole minutes, always sum to the total, and MAIN is always positive
 * for non-REST intents. QUALITY has no structure yet and is rejected explicitly.
 */
final class WorkoutPrescriptionPolicy {

    static final String QUALITY_NOT_SUPPORTED = "QUALITY_PRESCRIPTION_NOT_SUPPORTED";

    private WorkoutPrescriptionPolicy() {
    }

    static WorkoutPrescription prescribe(WorkoutRecommendation r) {
        CandidateTrainingType intent = r.recommendedIntent();
        if (intent == null) {
            throw new IllegalStateException("Recommendation has no intent");
        }
        if (intent == CandidateTrainingType.QUALITY) {
            throw new UnprocessableRequestException(QUALITY_NOT_SUPPORTED,
                    "A QUALITY workout structure is not supported yet");
        }
        int min = r.durationMinMinutes();
        int max = r.durationMaxMinutes();
        if (min < 0 || min > max) {
            throw new IllegalStateException("Invalid recommendation duration range " + min + ".." + max + " for " + intent);
        }

        int total = clamp(preferredMinutes(intent), min, max);
        List<WorkoutSegment> segments = segments(intent, total);
        verify(intent, total, min, max, segments);
        return new WorkoutPrescription(r.asOfDate(), intent, total, segments, summary(intent, total), r);
    }

    static int preferredMinutes(CandidateTrainingType intent) {
        return switch (intent) {
            case REST -> 0;
            case RECOVERY -> 30;
            case EASY, CROSS_TRAINING -> 45;
            case LONG -> 90;
            case QUALITY -> throw new IllegalArgumentException("QUALITY has no preferred duration");
        };
    }

    private static List<WorkoutSegment> segments(CandidateTrainingType intent, int total) {
        if (intent == CandidateTrainingType.REST) {
            return List.of(new WorkoutSegment(SegmentType.REST, 0, IntensityClass.NONE, "Rest day, no training"));
        }
        int warmUp;
        int coolDown;
        switch (intent) {
            case RECOVERY, CROSS_TRAINING -> {
                warmUp = 5;
                coolDown = 5;
            }
            case EASY -> {
                warmUp = roundToFive(total, 1, 5);       // about 20 %
                coolDown = roundToFive(total, 1, 10);    // about 10 %
            }
            case LONG -> {
                warmUp = Math.min(10, roundToFive(total, 1, 10));
                coolDown = warmUp;
            }
            default -> throw new IllegalArgumentException("No structure for " + intent);
        }
        // Short totals: warm-up and cool-down never take more than a quarter each, so MAIN stays at least half.
        warmUp = Math.min(warmUp, total / 4);
        coolDown = Math.min(coolDown, total / 4);
        int main = total - warmUp - coolDown;

        IntensityClass mainIntensity = intent == CandidateTrainingType.RECOVERY ? IntensityClass.VERY_EASY : IntensityClass.EASY;
        return List.of(
                new WorkoutSegment(SegmentType.WARM_UP, warmUp, IntensityClass.VERY_EASY, "Easy warm-up"),
                new WorkoutSegment(SegmentType.MAIN, main, mainIntensity, mainDescription(intent)),
                new WorkoutSegment(SegmentType.COOL_DOWN, coolDown, IntensityClass.VERY_EASY, "Easy cool-down"));
    }

    private static String mainDescription(CandidateTrainingType intent) {
        return switch (intent) {
            case RECOVERY -> "Very easy recovery running";
            case EASY -> "Steady easy running";
            case LONG -> "Steady easy long run";
            case CROSS_TRAINING -> "Steady easy cross-training, modality not specified";
            default -> throw new IllegalArgumentException("No structure for " + intent);
        };
    }

    private static String summary(CandidateTrainingType intent, int total) {
        if (intent == CandidateTrainingType.REST) {
            return "Rest day: no training is prescribed.";
        }
        String kind = switch (intent) {
            case RECOVERY -> "recovery";
            case EASY -> "easy";
            case LONG -> "long";
            case CROSS_TRAINING -> "cross-training";
            default -> throw new IllegalArgumentException("No structure for " + intent);
        };
        return total + "-minute " + kind + " session with warm-up, steady main work, and cool-down.";
    }

    /** Defensive check of the prescription invariants; a failure is a bug, never silently corrected. */
    private static void verify(CandidateTrainingType intent, int total, int min, int max, List<WorkoutSegment> segments) {
        int sum = segments.stream().mapToInt(WorkoutSegment::durationMinutes).sum();
        boolean nonNegative = segments.stream().allMatch(s -> s.durationMinutes() >= 0);
        boolean mainPositive = intent == CandidateTrainingType.REST
                || segments.stream().anyMatch(s -> s.type() == SegmentType.MAIN && s.durationMinutes() > 0);
        boolean restIsZero = intent != CandidateTrainingType.REST || total == 0;
        if (sum != total || !nonNegative || !mainPositive || !restIsZero || total < min || total > max) {
            throw new IllegalStateException("Prescription invariant violated for " + intent + ": total=" + total
                    + " sum=" + sum + " range=" + min + ".." + max);
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** round(total * numerator / denominator) to the nearest multiple of 5 (halves round up), integer arithmetic only. */
    private static int roundToFive(int total, int numerator, int denominator) {
        return (2 * total * numerator + 5 * denominator) / (10 * denominator) * 5;
    }
}
