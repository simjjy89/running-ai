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
 * <p>
 * {@code automaticSync} (Phase 8B) adds the third question an operator actually asks: will the
 * scheduler run the next one? It describes the AUTOMATIC path only. A manual POST to this same
 * endpoint's sibling is never blocked, whatever this says - so {@code allowed=false} means "the
 * scheduler is holding off", never "you cannot sync".
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record GarminSyncStatusResponse(
        boolean initialized,
        Instant highWaterStartedAt,
        Instant lastSuccessfulSyncAt,
        GarminSyncAttemptStatus lastAttempt,
        GarminSyncGateDecision automaticSync
) {

    /**
     * The last recorded sync attempt. Counts are those of that attempt (zero for a failed one);
     * {@code lastFailureAt} / {@code lastFailureCode} survive a later success on purpose, so
     * "when did this last break?" stays answerable, while {@code consecutiveFailureCount} returns
     * to zero as soon as an attempt completes.
     * <p>
     * {@code consecutivePartialCount} (Phase 8B) counts the other kind of stall: runs that
     * completed but left items unmapped, which hold the checkpoint back so the next run re-reads
     * the same window. It is separate from the failure streak because a PARTIAL genuinely is not a
     * failure - Garmin answered and the mappable items were stored - yet repeating it forever is
     * still a problem. {@code failureClass} is the stored code grouped into the kind of problem it
     * is (authentication, rate limit, transient), which is what decides how long the breaker waits.
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
            int consecutivePartialCount,
            GarminFailureClass failureClass,
            Instant lastFailureAt,
            String lastFailureCode
    ) {

        static GarminSyncAttemptStatus of(GarminSyncAttempt attempt) {
            return new GarminSyncAttemptStatus(
                    attempt.getAttemptedAt(), attempt.getOutcome(), attempt.getFailureCode(),
                    attempt.getFetched(), attempt.getCreated(), attempt.getUpdated(), attempt.getSkipped(),
                    attempt.getFailed(), attempt.getPagesFetched(), attempt.isCheckpointAdvanced(),
                    attempt.getConsecutiveFailureCount(), attempt.getConsecutivePartialCount(),
                    GarminFailureClass.of(attempt.getFailureCode()),
                    attempt.getLastFailureAt(), attempt.getLastFailureCode());
        }
    }
}
