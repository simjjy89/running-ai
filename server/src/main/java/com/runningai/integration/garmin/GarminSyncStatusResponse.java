package com.runningai.integration.garmin;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * {@code GET /api/v1/garmin/sync/status}. Timestamps are explicit nulls while not initialized.
 * <p>
 * {@code initialized} / {@code highWaterStartedAt} / {@code lastSuccessfulSyncAt} describe what has
 * SUCCEEDED; {@code lastAttempt} (Phase 8A, null until the first attempt is recorded) describes what
 * was last TRIED. The two together are what makes "Garmin had nothing new" distinguishable from
 * "nothing has run" and from "collection failed".
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record GarminSyncStatusResponse(
        boolean initialized,
        Instant highWaterStartedAt,
        Instant lastSuccessfulSyncAt,
        GarminSyncAttemptStatus lastAttempt
) {

    /**
     * The last recorded sync attempt. Counts are those of that attempt (zero for a failed one);
     * {@code lastFailureAt} / {@code lastFailureCode} survive a later success on purpose, so
     * "when did this last break?" stays answerable, while {@code consecutiveFailureCount} returns
     * to zero as soon as an attempt completes.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record GarminSyncAttemptStatus(
            Instant attemptedAt,
            GarminSyncAttemptOutcome outcome,
            String failureCode,
            int fetched,
            int created,
            int updated,
            int skipped,
            int failed,
            int pagesFetched,
            boolean checkpointAdvanced,
            int consecutiveFailureCount,
            Instant lastFailureAt,
            String lastFailureCode
    ) {

        static GarminSyncAttemptStatus of(GarminSyncAttempt attempt) {
            return new GarminSyncAttemptStatus(
                    attempt.getAttemptedAt(), attempt.getOutcome(), attempt.getFailureCode(),
                    attempt.getFetched(), attempt.getCreated(), attempt.getUpdated(), attempt.getSkipped(),
                    attempt.getFailed(), attempt.getPagesFetched(), attempt.isCheckpointAdvanced(),
                    attempt.getConsecutiveFailureCount(), attempt.getLastFailureAt(), attempt.getLastFailureCode());
        }
    }
}
