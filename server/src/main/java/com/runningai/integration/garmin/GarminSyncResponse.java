package com.runningai.integration.garmin;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * {@code POST /api/v1/garmin/sync} body. {@code checkpointAdvanced} is true only when the
 * high-water mark moved forward (or was created); a run that just refreshed
 * {@code lastSuccessfulSyncAt} reports false.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record GarminSyncResponse(
        int fetched,
        int created,
        int updated,
        int skipped,
        int failed,
        int pagesFetched,
        boolean checkpointAdvanced,
        Instant highWaterStartedAt,
        Instant lastSuccessfulSyncAt
) {

    static GarminSyncResponse of(GarminIncrementalSyncResult r, Instant lastSuccessfulSyncAt) {
        return new GarminSyncResponse(r.fetched(), r.created(), r.updated(), r.skipped(), r.failed(),
                r.pagesFetched(), r.checkpointAdvanced(), r.highWaterStartedAt(), lastSuccessfulSyncAt);
    }
}
