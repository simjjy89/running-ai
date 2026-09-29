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
     * @param start offset into Garmin's activity list, where 0 is the most recent activity
     * @param limit number of activities to fetch from {@code start} (1..100)
     * @return raw activity-list items, newest first
     * @throws GarminConnectorException when the connector or Garmin cannot serve the request
     */
    List<JsonNode> fetchActivities(int start, int limit);
}
