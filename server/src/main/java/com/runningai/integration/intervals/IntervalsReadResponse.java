package com.runningai.integration.intervals;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One Intervals.icu read, as received (Phase 6H-5): the body untouched, plus what the response said about
 * the request budget.
 *
 * @param body      the JSON exactly as Intervals.icu sent it - never narrowed, never renamed, so the raw
 *                  payload can be stored first and mapped afterwards against a verified contract
 * @param rateLimit observational only; nothing throttles or retries on it
 */
public record IntervalsReadResponse(JsonNode body, IntervalsRateLimit rateLimit) {

    public boolean isArray() {
        return body != null && body.isArray();
    }

    /** Number of elements for an array body, otherwise 0. Used for logging a count without the content. */
    public int size() {
        return isArray() ? body.size() : 0;
    }
}
