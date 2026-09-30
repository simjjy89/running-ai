package com.runningai.integration.garmin;

/** A Garmin sync is already running in this JVM; the new request was rejected without starting one. */
public class GarminSyncAlreadyRunningException extends RuntimeException {

    public GarminSyncAlreadyRunningException() {
        super("Garmin sync is already running");
    }
}
