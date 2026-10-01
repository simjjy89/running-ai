package com.runningai.integration.garmin;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Result of one Garmin athlete profile sync attempt: whether the stored profile changed, and its
 * state afterwards (current values whether or not this attempt changed anything). Never carries
 * the raw Garmin payload.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record GarminProfileSyncResponse(
        boolean updated,
        Integer lactateThresholdHeartRateBpm,
        Integer lactateThresholdPaceSecondsPerKm
) {
}
