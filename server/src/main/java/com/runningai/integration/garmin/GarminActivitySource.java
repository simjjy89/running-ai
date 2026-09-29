package com.runningai.integration.garmin;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Transport boundary between RunningAI and Garmin. Implementations return raw
 * Garmin activity-list items and know nothing about Garmin authentication,
 * tokens or the database.
 */
public interface GarminActivitySource {

    /**
     * @param limit number of most recent activities to fetch (1..100)
     * @return raw activity-list items, newest first
     * @throws GarminConnectorException when the connector or Garmin cannot serve the request
     */
    List<JsonNode> fetchRecentActivities(int limit);
}
