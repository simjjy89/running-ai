package com.runningai.integration.garmin;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/** {@code GET /api/v1/garmin/sync/status}. Timestamps are explicit nulls while not initialized. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record GarminSyncStatusResponse(boolean initialized, Instant highWaterStartedAt, Instant lastSuccessfulSyncAt) {
}
