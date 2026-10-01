package com.runningai.integration.intervals;

/**
 * Intervals.icu could not be reached or the publish could not be completed safely. Messages carry
 * status codes and field names only, never credentials, response bodies or workout text.
 */
public class IntervalsException extends RuntimeException {

    public enum Reason {
        /** No API key configured; no request was sent. */
        NOT_CONFIGURED,
        /** HTTP 401. */
        AUTH_FAILED,
        /** HTTP 403. */
        FORBIDDEN,
        /** HTTP 429: stop, do not retry automatically. */
        RATE_LIMITED,
        /** Any other 4xx (the request was rejected, nothing was changed). */
        CLIENT_ERROR,
        /** 5xx. */
        UPSTREAM_ERROR,
        /** Connect or read timeout. */
        TIMEOUT,
        /** Connection refused / reset / DNS failure. */
        CONNECTION_FAILED,
        /** 2xx with a body that is not the expected event shape. */
        INVALID_RESPONSE,
        /** More than one RunningAI-owned workout on the date: ambiguous, nothing was changed. */
        DUPLICATE_OWNED_WORKOUT,
        /** The date holds only workouts RunningAI does not own: nothing was created or changed. */
        UNMANAGED_WORKOUT_CONFLICT,
        /** A write returned 2xx but the server readback differs from what was published. */
        READBACK_MISMATCH,
        /** The rendered workout has no content (for example a REST day); nothing to publish. */
        EMPTY_WORKOUT
    }

    private final Reason reason;
    private final Integer httpStatus;
    private final String remoteEventId;

    public IntervalsException(Reason reason, Integer httpStatus, String message) {
        this(reason, httpStatus, null, message, null);
    }

    public IntervalsException(Reason reason, Integer httpStatus, String message, Throwable cause) {
        this(reason, httpStatus, null, message, cause);
    }

    public IntervalsException(Reason reason, Integer httpStatus, String remoteEventId, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.httpStatus = httpStatus;
        this.remoteEventId = remoteEventId;
    }

    public Reason getReason() {
        return reason;
    }

    /** Machine-readable code, for example {@code INTERVALS_READBACK_MISMATCH}. */
    public String getCode() {
        return "INTERVALS_" + reason.name();
    }

    /** HTTP status returned by Intervals.icu, or null when there was none. */
    public Integer getHttpStatus() {
        return httpStatus;
    }

    /** The event involved when a write already happened (readback mismatch), for manual inspection; else null. */
    public String getRemoteEventId() {
        return remoteEventId;
    }

    /**
     * True when a write may or may not have reached the server (timeout, dropped connection, 5xx), so the
     * caller must find out by looking the event up, never by sending the same write again.
     */
    public boolean isOutcomeUnknown() {
        return reason == Reason.TIMEOUT || reason == Reason.CONNECTION_FAILED || reason == Reason.UPSTREAM_ERROR;
    }
}
