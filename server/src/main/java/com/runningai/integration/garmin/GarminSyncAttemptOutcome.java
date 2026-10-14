package com.runningai.integration.garmin;

/**
 * How the last Garmin incremental sync attempt ended. Recorded for observability only (Phase 8A):
 * nothing in the sync path reads it, and it can never influence {@link GarminSyncState}.
 */
public enum GarminSyncAttemptOutcome {

    /** The run completed and every fetched item was ingested. */
    SUCCESS,

    /**
     * The run completed, but at least one item could not be mapped. The checkpoint is deliberately
     * not advanced in this case, so the next run re-covers the same window.
     */
    PARTIAL,

    /** The run threw: a connector/Garmin failure, or an incomplete overlap window. */
    FAILED
}
