package com.runningai.integration.intervals;

import java.time.LocalDate;

/**
 * The parts of an Intervals.icu calendar event the publisher cares about. Fields the server omits are null.
 *
 * @param id            server event id (opaque text)
 * @param scheduledDate local calendar date of {@code start_date_local}
 * @param externalId    Intervals {@code external_id}; RunningAI's ownership marker lives here
 */
public record IntervalsEvent(
        String id,
        LocalDate scheduledDate,
        String name,
        String type,
        String description,
        String externalId
) {

    /** Never prints the description (workout text). */
    @Override
    public String toString() {
        return "IntervalsEvent[date=" + scheduledDate + ", hasDescription=" + (description != null && !description.isBlank()) + "]";
    }
}
