package com.runningai.integration.garmin;

/**
 * What KIND of problem a stored {@code failure_code} describes (Phase 8B).
 * <p>
 * The distinction exists because the right response differs completely. A rejected credential will
 * be rejected again in an hour and in a day: repeating it is pure noise against Garmin and can
 * itself look like an attack, and only a human can fix it. A rate limit is the opposite - the
 * account is fine and waiting is the whole remedy. Treating both as "a failure" and counting them
 * into one number is what lets a stuck sync hammer Garmin 24 times a day.
 * <p>
 * Classification is by code only, never by message, so nothing here can be influenced by text that
 * came back from Garmin.
 */
public enum GarminFailureClass {

    /**
     * Garmin refused who we are (401/403). No amount of waiting fixes it; the session or the
     * credentials must be renewed on the connector side by a human.
     */
    AUTH,

    /** Garmin refused how often we ask (429). Waiting is the fix, and the only fix. */
    RATE_LIMIT,

    /**
     * The connector, the network or Garmin's own servers failed, or the overlap window could not be
     * completed. Usually self-healing, so it is tolerated far longer than the other two - but not
     * forever, because an unreachable connector that is never going to come back should not be
     * polled hourly either.
     */
    TRANSIENT,

    /** No attempt has failed, or the code is not one this version knows. Treated as TRANSIENT. */
    UNKNOWN;

    /**
     * Maps a stored {@code failure_code} to its class. An unrecognised code deliberately becomes
     * {@link #UNKNOWN} rather than throwing: the ledger is written by the running version and read
     * by whatever version is running later, and a breaker that crashes on an unfamiliar string
     * would be worse than one that falls back to the most forgiving class.
     */
    public static GarminFailureClass of(String failureCode) {
        if (failureCode == null || failureCode.isBlank()) {
            return UNKNOWN;
        }
        return switch (failureCode) {
            case "GARMIN_AUTH_REQUIRED", "GARMIN_FORBIDDEN" -> AUTH;
            case "GARMIN_RATE_LIMITED" -> RATE_LIMIT;
            case "GARMIN_UNAVAILABLE", "GARMIN_UPSTREAM_ERROR", "GARMIN_CONNECTOR_ERROR",
                 "GARMIN_INVALID_RESPONSE", "GARMIN_NOT_FOUND",
                 "INCREMENTAL_WINDOW_INCOMPLETE" -> TRANSIENT;
            default -> failureCode.startsWith("GARMIN_") || failureCode.startsWith("UNEXPECTED_")
                    ? TRANSIENT
                    : UNKNOWN;
        };
    }
}
