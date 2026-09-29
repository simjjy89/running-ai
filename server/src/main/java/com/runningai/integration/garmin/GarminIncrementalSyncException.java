package com.runningai.integration.garmin;

/**
 * An incremental sync exhausted {@code max-pages} before its overlap cutoff was
 * reached ({@code INCREMENTAL_WINDOW_INCOMPLETE}): the window is not fully known
 * to be covered, so {@link GarminSyncState} is not advanced. Activities already
 * ingested by this run are not rolled back; the next sync re-covers the same
 * window via the overlap and idempotent ingestion.
 */
public class GarminIncrementalSyncException extends RuntimeException {

    public GarminIncrementalSyncException(String message) {
        super(message);
    }
}
