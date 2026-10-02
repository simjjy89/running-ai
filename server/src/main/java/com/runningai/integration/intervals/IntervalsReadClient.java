package com.runningai.integration.intervals;

import java.time.LocalDate;

/**
 * Read-only access to Intervals.icu for analysis enrichment (Phase 6H-5).
 * <p>
 * Deliberately separate from {@link IntervalsWorkoutClient}: that one can create and update events, and
 * enrichment must not be able to reach those methods even by accident. <strong>This interface declares no
 * write operation and its implementation issues GET only</strong> - there is no code path from enrichment
 * to a POST, PUT or DELETE against Intervals.icu.
 * <p>
 * Every method returns the response body untouched. The field names Intervals.icu uses are not interpreted
 * here, and were not guessed anywhere else either: the raw payload is what gets stored first, and mapping
 * follows a verified live contract.
 * <p>
 * Exactly one request per call and never a retry. A 429 raises {@link IntervalsException.Reason#RATE_LIMITED}
 * and the caller stops; nothing waits out a {@code Retry-After} on its own.
 */
public interface IntervalsReadClient {

    /**
     * Completed activities in a date range (inclusive), for the athlete that owns the API key.
     *
     * @return the response body as received, normally a JSON array
     */
    IntervalsReadResponse listActivities(LocalDate oldest, LocalDate newest);

    /** One activity by its Intervals.icu id. */
    IntervalsReadResponse getActivity(String intervalsActivityId);

    /**
     * One activity, optionally asking Intervals.icu to include its interval/lap breakdown.
     * <p>
     * RunningAI already stores Garmin's laps and samples as the sensor source of truth, so a richer
     * Intervals answer is kept for reference rather than used to replace them.
     */
    IntervalsReadResponse getActivity(String intervalsActivityId, boolean includeIntervals);

    /**
     * Wellness entries in a date range (inclusive). This is where Intervals.icu exposes its own
     * fitness-model numbers; what each field means is settled by the live contract, not by its name.
     */
    IntervalsReadResponse getWellness(LocalDate oldest, LocalDate newest);
}
