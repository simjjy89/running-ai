package com.runningai.integration.garmin;

/** A recovery sync or backfill is already running in this JVM; the new request started nothing. */
public class GarminRecoverySyncAlreadyRunningException extends RuntimeException {

    public GarminRecoverySyncAlreadyRunningException() {
        super("Garmin recovery sync is already running");
    }
}
