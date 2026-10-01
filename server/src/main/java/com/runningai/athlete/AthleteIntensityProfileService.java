package com.runningai.athlete;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Reads and replaces the default athlete's intensity profile. Not exposed as an
 * entity outside this package; callers (the controller, and
 * {@code com.runningai.training.WorkoutIntensityTargetService}) only ever see
 * {@link AthleteIntensityProfileResponse}.
 */
@Service
@Transactional(readOnly = true)
public class AthleteIntensityProfileService {

    private final AthleteIntensityProfileRepository repository;
    private final AthleteService athleteService;

    public AthleteIntensityProfileService(AthleteIntensityProfileRepository repository, AthleteService athleteService) {
        this.repository = repository;
        this.athleteService = athleteService;
    }

    public AthleteIntensityProfileResponse getDefaultProfile() {
        Long athleteId = athleteService.getDefaultAthlete().getId();
        return repository.findByAthleteId(athleteId)
                .map(AthleteIntensityProfileService::toResponse)
                .orElseGet(AthleteIntensityProfileResponse::empty);
    }

    /** PUT semantics: creates the profile on the first call, otherwise replaces both metrics in place. */
    @Transactional
    public AthleteIntensityProfileResponse replaceDefaultProfile(AthleteIntensityProfileRequest request) {
        Long athleteId = athleteService.getDefaultAthlete().getId();
        AthleteIntensityProfile profile = repository.findByAthleteId(athleteId)
                .map(existing -> {
                    existing.update(request.lactateThresholdHeartRateBpm(), request.lactateThresholdPaceSecondsPerKm());
                    return existing;
                })
                .orElseGet(() -> repository.save(new AthleteIntensityProfile(athleteId,
                        request.lactateThresholdHeartRateBpm(), request.lactateThresholdPaceSecondsPerKm())));
        return toResponse(profile);
    }

    /**
     * Garmin-sync upsert, distinct from the PUT semantics of {@link #replaceDefaultProfile}: a
     * {@code null} input metric here means "Garmin did not report it right now" and leaves the
     * stored value untouched (never clears it); a non-null input metric always overwrites the
     * stored value with Garmin's latest. No row is written at all when neither resolved value
     * differs from what is already stored (both inputs {@code null}, or both equal to the
     * current values).
     */
    @Transactional
    public GarminProfileMergeResult mergeGarminSnapshot(Integer lactateThresholdHeartRateBpmFromGarmin,
                                                         Integer lactateThresholdPaceSecondsPerKmFromGarmin) {
        Long athleteId = athleteService.getDefaultAthlete().getId();
        var existingOpt = repository.findByAthleteId(athleteId);
        Integer existingBpm = existingOpt.map(AthleteIntensityProfile::getLactateThresholdHeartRateBpm).orElse(null);
        Integer existingPace = existingOpt.map(AthleteIntensityProfile::getLactateThresholdPaceSecondsPerKm).orElse(null);

        Integer resolvedBpm = lactateThresholdHeartRateBpmFromGarmin != null
                ? lactateThresholdHeartRateBpmFromGarmin : existingBpm;
        Integer resolvedPace = lactateThresholdPaceSecondsPerKmFromGarmin != null
                ? lactateThresholdPaceSecondsPerKmFromGarmin : existingPace;

        boolean heartRateChanged = !Objects.equals(resolvedBpm, existingBpm);
        boolean paceChanged = !Objects.equals(resolvedPace, existingPace);

        if (!heartRateChanged && !paceChanged) {
            AthleteIntensityProfileResponse current = existingOpt.map(AthleteIntensityProfileService::toResponse)
                    .orElseGet(AthleteIntensityProfileResponse::empty);
            return new GarminProfileMergeResult(false, false, false, current);
        }

        AthleteIntensityProfile profile = existingOpt
                .map(existing -> {
                    existing.update(resolvedBpm, resolvedPace);
                    return existing;
                })
                .orElseGet(() -> repository.save(new AthleteIntensityProfile(athleteId, resolvedBpm, resolvedPace)));
        return new GarminProfileMergeResult(true, heartRateChanged, paceChanged, toResponse(profile));
    }

    private static AthleteIntensityProfileResponse toResponse(AthleteIntensityProfile profile) {
        return new AthleteIntensityProfileResponse(true, profile.getLactateThresholdHeartRateBpm(),
                profile.getLactateThresholdPaceSecondsPerKm());
    }
}
