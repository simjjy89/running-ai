package com.runningai.integration.garmin;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Ad hoc Garmin activity-list items for incremental sync tests, where the exact
 * {@code startTimeGMT} of each item (relative to a cutoff) is what is under test.
 */
final class GarminIncrementalSyncFixtures {

    private GarminIncrementalSyncFixtures() {
    }

    static JsonNode activity(long activityId, String typeKey, String startTimeGmt) {
        return activityWithDistance(activityId, typeKey, startTimeGmt, 5000.0);
    }

    static JsonNode activityWithDistance(long activityId, String typeKey, String startTimeGmt, double distanceMeters) {
        return GarminFixtures.json("""
                {"activityId": %d, "activityType": {"typeKey": "%s"}, "startTimeGMT": "%s",
                 "duration": 1800.0, "distance": %s, "averageHR": 140.0, "maxHR": 160.0}
                """.formatted(activityId, typeKey, startTimeGmt, distanceMeters));
    }

    static JsonNode malformedNoStartTime(long activityId) {
        return GarminFixtures.json("""
                {"activityId": %d, "activityType": {"typeKey": "running"}, "duration": 1800.0}
                """.formatted(activityId));
    }
}
