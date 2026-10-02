package com.runningai.integration.garmin;

/**
 * The Garmin connector (or Garmin behind it) could not serve a request. This is a
 * connector-level failure: a sync must abort, never treat it as an empty result.
 */
public class GarminConnectorException extends RuntimeException {

    public enum Reason {
        /** Connector answered 401 GARMIN_AUTH_REQUIRED: a human must log in on the connector host. */
        AUTH_REQUIRED,
        /** Connector answered 403 GARMIN_FORBIDDEN. */
        FORBIDDEN,
        /** Connector answered 429 GARMIN_RATE_LIMITED: stop, do not retry automatically. */
        RATE_LIMITED,
        /** Connector answered 404 GARMIN_NOT_FOUND: Garmin has no such resource (e.g. an unknown activity id). */
        NOT_FOUND,
        /** Connector answered 502 GARMIN_UPSTREAM_ERROR. */
        UPSTREAM_ERROR,
        /** Connector answered 500 or another unexpected status. */
        CONNECTOR_ERROR,
        /** Connector process not reachable (connection refused, timeout). */
        UNAVAILABLE,
        /** Connector answered 200 but not with a JSON array. */
        INVALID_RESPONSE
    }

    private final Reason reason;
    private final Integer httpStatus;

    public GarminConnectorException(Reason reason, Integer httpStatus, String message) {
        super(message);
        this.reason = reason;
        this.httpStatus = httpStatus;
    }

    public GarminConnectorException(Reason reason, Integer httpStatus, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.httpStatus = httpStatus;
    }

    public Reason getReason() {
        return reason;
    }

    /** HTTP status returned by the connector, or null when it was not reachable. */
    public Integer getHttpStatus() {
        return httpStatus;
    }
}
