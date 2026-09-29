package com.runningai.integration.garmin;

/**
 * Outcome of one successful Garmin payload ingestion or reprocessing.
 */
public record GarminIngestionResult(
        String garminActivityId,
        Long activityId,
        Long activityRawId,
        Action action
) {

    public enum Action {
        /** A new Activity row was inserted. */
        CREATED,
        /** An existing Activity row (same external identity) was updated in place. */
        UPDATED
    }

    public boolean created() {
        return action == Action.CREATED;
    }

    public boolean updated() {
        return action == Action.UPDATED;
    }
}
