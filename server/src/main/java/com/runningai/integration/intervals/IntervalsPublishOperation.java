package com.runningai.integration.intervals;

public enum IntervalsPublishOperation {
    /** No owned workout existed on the date; one was created. */
    CREATED,
    /** An owned workout existed with different content; it was updated in place (same event id). */
    UPDATED,
    /** The owned workout already matched; no write request was sent. */
    NO_CHANGE
}
