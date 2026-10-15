package com.runningai.integration.garmin;

/**
 * How a Garmin incremental sync attempt ended - or that it has not ended yet.
 * <p>
 * Phase 8A recorded only the three terminal outcomes, for observability alone. Phase 8B adds the
 * two states that were missing once {@link GarminSyncCircuitBreaker} had to reason about the
 * ledger: a run that is still going, and a run that was killed. Nothing here can influence
 * {@link GarminSyncState}, and nothing in the fetch path reads it to decide what to collect.
 */
public enum GarminSyncAttemptOutcome {

    /**
     * The run has begun and has not reported back. Written before the connector is called and
     * overwritten by the real outcome, so it is only ever observed while a sync is in flight - or
     * after the JVM died during one, which is exactly what makes an interruption detectable.
     */
    STARTED,

    /** The run completed and every fetched item was ingested. */
    SUCCESS,

    /**
     * The run completed, but at least one item could not be mapped. The checkpoint is deliberately
     * not advanced in this case, so the next run re-covers the same window.
     */
    PARTIAL,

    /** The run threw: a connector/Garmin failure, or an incomplete overlap window. */
    FAILED,

    /**
     * The process died while the run was in flight (watchdog restart, sleep, power loss), found as
     * a leftover {@link #STARTED} row at the next startup. Not a failure: Garmin refused nothing,
     * so it never counts towards the consecutive-failure streak. Already-committed rows survive and
     * the checkpoint was not advanced, so the next run simply re-covers the window.
     */
    INTERRUPTED
}
