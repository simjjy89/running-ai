package com.runningai.integration.intervals;

/**
 * The Intervals.icu Workout Builder text produced by {@link IntervalsWorkoutRenderer}.
 * Render-only: no HTTP payload, URL, auth header, remote event id or publish status --
 * those belong to a future publisher phase, not this one.
 */
public record RenderedIntervalsWorkout(String workoutText) {
}
