package com.runningai.athlete;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * {@code initialized = false} means no profile has been saved yet (both metrics
 * {@code null}); this is a valid, common state, never a 404.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AthleteIntensityProfileResponse(
        boolean initialized,
        Integer lactateThresholdHeartRateBpm,
        Integer lactateThresholdPaceSecondsPerKm
) {

    public static AthleteIntensityProfileResponse empty() {
        return new AthleteIntensityProfileResponse(false, null, null);
    }
}
