package com.runningai.integration.intervals;

/**
 * What an Intervals.icu response said about the caller's request budget (Phase 6H-5).
 * <p>
 * Observational only: nothing in RunningAI waits, throttles or retries based on these values. A 429 stops
 * the run and is reported; a {@code Retry-After} is recorded so a human can decide when to try again.
 *
 * @param limit     {@code X-RateLimit-Limit}, or null when the response did not carry it
 * @param remaining {@code X-RateLimit-Remaining}, or null when the response did not carry it
 * @param retryAfter {@code Retry-After} exactly as sent, or null
 */
public record IntervalsRateLimit(Integer limit, Integer remaining, String retryAfter) {

    static final IntervalsRateLimit UNKNOWN = new IntervalsRateLimit(null, null, null);

    /** Safe for logs: no credential can reach this, and an absent header prints as a dash. */
    @Override
    public String toString() {
        return "remaining=" + text(remaining) + "/" + text(limit)
                + (retryAfter == null ? "" : " retryAfter=" + retryAfter);
    }

    private static String text(Integer value) {
        return value == null ? "-" : value.toString();
    }
}
