package com.runningai.integration.intervals;

import java.time.LocalDate;

/** What the publisher asks the server to store for one WORKOUT event. */
public record IntervalsEventDraft(
        LocalDate scheduledDate,
        String name,
        String type,
        String description,
        String externalId
) {

    /** Never prints the description (workout text). */
    @Override
    public String toString() {
        return "IntervalsEventDraft[date=" + scheduledDate + "]";
    }
}
