package com.runningai.integration.garmin;

/**
 * Normalised latest Garmin running lactate-threshold snapshot. Either field may be {@code null}
 * when Garmin did not report it or it failed validation (see {@link GarminLactateThresholdMapper});
 * {@code null} here always means "not available from Garmin right now", never "clear the stored
 * value" - that distinction is enforced by the merge policy in
 * {@code com.runningai.athlete.AthleteIntensityProfileService#mergeGarminSnapshot}.
 */
public record GarminLactateThresholdSnapshot(
        Integer lactateThresholdHeartRateBpm,
        Integer lactateThresholdPaceSecondsPerKm
) {

    static final GarminLactateThresholdSnapshot EMPTY = new GarminLactateThresholdSnapshot(null, null);
}
