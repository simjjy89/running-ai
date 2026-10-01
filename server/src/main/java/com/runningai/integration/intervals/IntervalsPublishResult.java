package com.runningai.integration.intervals;

import java.time.LocalDate;

/**
 * Outcome of one publish. {@code verified} is true only when the server state (a readback after a write,
 * or the just-listed event for NO_CHANGE) matched the rendered workout; a mismatch throws instead.
 */
public record IntervalsPublishResult(
        IntervalsPublishOperation operation,
        String remoteEventId,
        boolean verified,
        LocalDate scheduledDate
) {
}
