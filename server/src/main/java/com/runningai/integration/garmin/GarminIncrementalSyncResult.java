package com.runningai.integration.garmin;

import java.time.Instant;

/**
 * Outcome of one {@link GarminIncrementalSyncService#syncIncremental()} run.
 *
 * @param fetched            items received from the connector across all pages
 * @param created             activities inserted
 * @param updated             existing activities updated in place
 * @param skipped             items stored as raw but not normalised (unsupported activity type)
 * @param failed              items that could not be ingested (malformed payload)
 * @param pagesFetched        number of connector pages fetched this run
 * @param checkpointAdvanced  whether the high-water mark moved forward (or the checkpoint was created) this
 *                            run; false when only {@code lastSuccessfulSyncAt} was refreshed or the run failed
 * @param highWaterStartedAt  the checkpoint's high-water mark after this run (unchanged when not advanced), or
 *                            {@code null} when no checkpoint exists yet
 */
public record GarminIncrementalSyncResult(
        int fetched,
        int created,
        int updated,
        int skipped,
        int failed,
        int pagesFetched,
        boolean checkpointAdvanced,
        Instant highWaterStartedAt
) {

    public int ingested() {
        return created + updated;
    }
}
