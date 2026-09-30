package com.runningai.training;

/**
 * Which numeric target (if any) a renderer should treat as primary for a segment.
 * Pace is preferred over heart rate when both are available (see
 * {@link WorkoutIntensityTargetPolicy}) — a runtime/product choice, not a claim
 * that pace is physiologically superior.
 */
public enum PrimaryTargetType {
    /** No target applies (a REST segment). */
    NONE,
    PACE,
    HEART_RATE,
    /** No numeric target available; only the qualitative {@code intensityClass} applies. */
    QUALITATIVE
}
