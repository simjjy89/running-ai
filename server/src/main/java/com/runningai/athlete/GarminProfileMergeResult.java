package com.runningai.athlete;

/**
 * Outcome of {@link AthleteIntensityProfileService#mergeGarminSnapshot}: whether the stored
 * profile actually changed, which metric(s) changed, and the profile's state afterwards (current
 * state even when nothing changed).
 */
public record GarminProfileMergeResult(
        boolean updated,
        boolean heartRateChanged,
        boolean paceChanged,
        AthleteIntensityProfileResponse profile
) {
}
