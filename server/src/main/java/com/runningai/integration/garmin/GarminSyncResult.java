package com.runningai.integration.garmin;

/**
 * Outcome of one {@link GarminSyncService#syncRecent(int)} run.
 *
 * @param fetched items received from the connector
 * @param created activities inserted
 * @param updated existing activities updated in place (idempotent re-sync)
 * @param skipped items stored as raw but not normalised because the activity type is unsupported
 * @param failed  items that could not be ingested (malformed payload); raw is kept when an id existed
 */
public record GarminSyncResult(int fetched, int created, int updated, int skipped, int failed) {

    public int ingested() {
        return created + updated;
    }
}
