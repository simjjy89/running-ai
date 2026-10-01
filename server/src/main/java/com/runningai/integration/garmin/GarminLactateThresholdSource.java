package com.runningai.integration.garmin;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Transport boundary for the latest Garmin running lactate-threshold snapshot. Implementations
 * return the raw connector JSON and know nothing about Garmin authentication, tokens or the
 * database; normalisation into domain values is {@link GarminLactateThresholdMapper}'s job.
 */
public interface GarminLactateThresholdSource {

    /**
     * @return raw {@code GET /lactate-threshold} body, exactly as the connector returned it
     * @throws GarminConnectorException when the connector or Garmin cannot serve the request
     */
    JsonNode fetchLatest();
}
