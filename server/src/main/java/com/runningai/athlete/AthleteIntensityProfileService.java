package com.runningai.athlete;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private static AthleteIntensityProfileResponse toResponse(AthleteIntensityProfile profile) {
        return new AthleteIntensityProfileResponse(true, profile.getLactateThresholdHeartRateBpm(),
                profile.getLactateThresholdPaceSecondsPerKm());
    }
}
