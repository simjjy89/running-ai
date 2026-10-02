package com.runningai.integration.garmin;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;

/**
 * Transport boundary for one day of Garmin recovery metrics (Phase 6F). Implementations return the
 * connector's {@code GET /recovery} JSON and know nothing about Garmin authentication or the
 * database; normalisation is {@link GarminRecoveryMapper}'s job.
 */
public interface GarminRecoveryClient {

    /**
     * @return the connector's {@code {"date", "metrics": {...}}} body for {@code date}
     * @throws GarminConnectorException when the connector or Garmin cannot serve the request
     *                                  (authentication, rate limit, connector down, ...)
     */
    JsonNode fetchDay(LocalDate date);
}
