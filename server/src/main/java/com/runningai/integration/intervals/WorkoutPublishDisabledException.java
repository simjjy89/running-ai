package com.runningai.integration.intervals;

/** Workout publishing is switched off ({@code running-ai.workout-publishing.enabled=false}); nothing was fetched or written. */
public class WorkoutPublishDisabledException extends RuntimeException {

    public WorkoutPublishDisabledException() {
        super("Workout publishing is disabled (running-ai.workout-publishing.enabled / WORKOUT_PUBLISHING_ENABLED)");
    }
}
