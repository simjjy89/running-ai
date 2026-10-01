package com.runningai.integration.intervals;

import java.time.LocalDate;
import java.util.List;

/**
 * Transport to Intervals.icu calendar events (category WORKOUT): authentication, HTTP and mapping only.
 * No idempotency, ownership or comparison logic lives here, and no method retries: every call is exactly
 * one request, so a timeout on {@link #create} can never silently produce a second event.
 */
public interface IntervalsWorkoutClient {

    /** All WORKOUT events whose local date is {@code date} (a one-day window). */
    List<IntervalsEvent> listWorkouts(LocalDate date);

    IntervalsEvent get(String eventId);

    /** One POST, never retried. */
    IntervalsEvent create(IntervalsEventDraft draft);

    /** One PUT on the existing event id. */
    IntervalsEvent update(String eventId, IntervalsEventDraft draft);
}
