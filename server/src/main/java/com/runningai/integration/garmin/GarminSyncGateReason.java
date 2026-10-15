package com.runningai.integration.garmin;

/** Why {@link GarminSyncCircuitBreaker} let a scheduled sync through, or did not (Phase 8B). */
public enum GarminSyncGateReason {

    /** Nothing is blocking. */
    ALLOWED,

    /**
     * Garmin has rejected our identity this many times in a row. Only a human can fix it (renew the
     * connector's Garmin session), and a manual sync is how they confirm the fix - so this block
     * has no expiry time and is lifted by a successful attempt, never by the clock.
     */
    AUTH_FAILURES,

    /** Garmin asked us to slow down and the cooldown has not elapsed. */
    RATE_LIMITED,

    /** Too many consecutive connector/network/upstream failures; pausing until the cooldown ends. */
    TRANSIENT_FAILURES,

    /**
     * Too many consecutive runs completed with unmappable items. The checkpoint has not moved
     * through any of them, so continuing would re-read the same window and re-hit the same item.
     */
    PARTIAL_STALL,

    /**
     * The previous attempt began less than the configured minimum interval ago. A ceiling on how
     * often RunningAI may ask Garmin anything on the automatic path, whatever the schedule says.
     */
    MINIMUM_INTERVAL,

    /** Another Garmin operation (a recovery sync or backfill) is running in this JVM right now. */
    GARMIN_BUSY
}
