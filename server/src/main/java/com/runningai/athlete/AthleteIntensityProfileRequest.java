package com.runningai.athlete;

import jakarta.validation.constraints.Positive;

/**
 * Complete replacement of the athlete's intensity profile (PUT semantics): a
 * {@code null} field means that metric is not set, not "leave unchanged".
 */
public record AthleteIntensityProfileRequest(
        @Positive Integer lactateThresholdHeartRateBpm,
        @Positive Integer lactateThresholdPaceSecondsPerKm
) {
}
