package com.runningai.integration.garmin;

/**
 * A Garmin payload could not be turned into a normalised activity. The raw
 * payload is still preserved by the ingestion service whenever an activity id
 * could be extracted; {@link #getReason()} tells callers why mapping stopped.
 */
public class GarminActivityMappingException extends RuntimeException {

    public enum Reason {
        ACTIVITY_ID_MISSING("GARMIN_ACTIVITY_ID_MISSING"),
        ACTIVITY_TYPE_MISSING("GARMIN_ACTIVITY_TYPE_MISSING"),
        UNSUPPORTED_ACTIVITY_TYPE("UNSUPPORTED_GARMIN_ACTIVITY_TYPE"),
        START_TIME_MISSING("GARMIN_ACTIVITY_START_TIME_MISSING"),
        START_TIME_INVALID("GARMIN_ACTIVITY_START_TIME_INVALID"),
        DURATION_MISSING("GARMIN_ACTIVITY_DURATION_MISSING"),
        INVALID_VALUE("GARMIN_ACTIVITY_MAPPING_FAILED");

        private final String code;

        Reason(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private final Reason reason;
    private final String garminActivityId;

    public GarminActivityMappingException(Reason reason, String garminActivityId, String message) {
        super(reason.code() + ": " + message);
        this.reason = reason;
        this.garminActivityId = garminActivityId;
    }

    public Reason getReason() {
        return reason;
    }

    public String getCode() {
        return reason.code();
    }

    /** May be null when the id itself is what is missing. */
    public String getGarminActivityId() {
        return garminActivityId;
    }
}
