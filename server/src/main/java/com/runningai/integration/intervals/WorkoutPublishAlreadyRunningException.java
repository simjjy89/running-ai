package com.runningai.integration.intervals;

import java.time.LocalDate;

/** A publish for the same date is already running in this JVM; the new request was rejected without doing anything. */
public class WorkoutPublishAlreadyRunningException extends RuntimeException {

    public WorkoutPublishAlreadyRunningException(LocalDate date) {
        super("A workout publish for " + date + " is already running");
    }
}
