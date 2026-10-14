package com.runningai.integration.garmin;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.util.List;

/**
 * Result of a recovery backfill. Days are processed newest first; a connector failure (rate limit,
 * authentication, connector down, ...) stops the run immediately - nothing is retried - and the
 * days already stored are kept.
 *
 * @param onlyMissing   whether the run was asked to skip days already covered (Phase 8A)
 * @param fetchedDays   days actually fetched from Garmin
 * @param skippedDays   days inside the window that were not fetched because {@code onlyMissing} found
 *                      them already complete, or already confirmed to have no Garmin data
 * @param completed     false when the run stopped before reaching {@code startDate}
 * @param stoppedAt     the day whose fetch failed, or null when completed
 * @param stoppedReason the connector failure reason (e.g. {@code RATE_LIMITED}), or null when completed
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record GarminRecoveryBackfillResponse(
        LocalDate startDate,
        LocalDate endDate,
        int requestedDays,
        boolean onlyMissing,
        int fetchedDays,
        int skippedDays,
        int updatedDays,
        boolean completed,
        LocalDate stoppedAt,
        String stoppedReason,
        List<GarminRecoverySyncResponse> days
) {
}
