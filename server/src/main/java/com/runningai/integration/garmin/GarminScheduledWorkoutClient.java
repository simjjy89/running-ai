package com.runningai.integration.garmin;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.YearMonth;

/**
 * Transport boundary for Garmin's own workout calendar (Phase 8B). Read-only, and deliberately
 * one-directional: there is no publish, schedule or push counterpart here and there must never be
 * one. RunningAI writes workouts to Intervals.icu; the hop from Intervals.icu to Garmin Connect is
 * Intervals.icu's own integration, outside this repository.
 */
public interface GarminScheduledWorkoutClient {

    /**
     * @return the connector's {@code GET /scheduled-workouts} body for {@code month}, unchanged
     * @throws GarminConnectorException when the connector or Garmin cannot serve the request
     */
    JsonNode fetchMonth(YearMonth month);
}
