package com.runningai.athlete;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads and replaces the default athlete's intensity profile. Not exposed as an
 * entity outside this package; callers (the controller, and
 * {@code com.runningai.training.WorkoutIntensityTargetService}) only ever see
 * {@link AthleteIntensityProfileResponse}.
 * <p>
 * Phase 7B-4: the two (and only two — audited) write paths, {@link #replaceDefaultProfile} and
 * {@link #mergeGarminSnapshot}, now append a {@link AthleteIntensityProfileHistory} row in the
 * SAME transaction as the profile write: if the ledger insert fails, the profile change rolls
 * back with it. Both paths take a pessimistic row lock first, so concurrent writes serialize and
 * every history row's previous-values snapshot reflects the value it actually replaced. Same-value
 * events are recorded with {@code applied=false} (observation, not a change). No backfill is ever
 * written for values that predate the ledger.
 */
@Service
@Transactional(readOnly = true)
public class AthleteIntensityProfileService {

    private final AthleteIntensityProfileRepository repository;
    private final AthleteIntensityProfileHistoryRepository history;
    private final AthleteService athleteService;
    private final Clock clock;

    public AthleteIntensityProfileService(AthleteIntensityProfileRepository repository,
                                          AthleteIntensityProfileHistoryRepository history,
                                          AthleteService athleteService, Clock clock) {
        this.repository = repository;
        this.history = history;
        this.athleteService = athleteService;
        this.clock = clock;
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
        Optional<AthleteIntensityProfile> existingOpt = repository.findWithLockingByAthleteId(athleteId);
        Integer previousBpm = existingOpt.map(AthleteIntensityProfile::getLactateThresholdHeartRateBpm).orElse(null);
        Integer previousPace = existingOpt.map(AthleteIntensityProfile::getLactateThresholdPaceSecondsPerKm).orElse(null);

        boolean changed = !Objects.equals(previousBpm, request.lactateThresholdHeartRateBpm())
                || !Objects.equals(previousPace, request.lactateThresholdPaceSecondsPerKm())
                || existingOpt.isEmpty();

        AthleteIntensityProfile profile = existingOpt
                .map(existing -> {
                    existing.update(request.lactateThresholdHeartRateBpm(), request.lactateThresholdPaceSecondsPerKm());
                    return existing;
                })
                .orElseGet(() -> repository.save(new AthleteIntensityProfile(athleteId,
                        request.lactateThresholdHeartRateBpm(), request.lactateThresholdPaceSecondsPerKm())));

        // Same transaction as the profile write: a ledger failure rolls the change back too.
        history.save(new AthleteIntensityProfileHistory(athleteId,
                AthleteIntensityProfileHistory.Source.MANUAL, changed,
                request.lactateThresholdHeartRateBpm(), request.lactateThresholdPaceSecondsPerKm(),
                previousBpm, previousPace,
                null, null, Instant.now(clock)));
        return toResponse(profile);
    }

    /**
     * Garmin-sync upsert, distinct from the PUT semantics of {@link #replaceDefaultProfile}: a
     * {@code null} input metric here means "Garmin did not report it right now" and leaves the
     * stored value untouched (never clears it); a non-null input metric always overwrites the
     * stored value with Garmin's latest. The profile row is only written when a resolved value
     * actually differs, but EVERY sync outcome appends a history row — {@code applied=false} for a
     * same-value re-observation (which may still carry a NEW Garmin calendarDate: freshness
     * evidence, never retroactive origin proof).
     *
     * @param garminCalendarDateRaw Garmin's own measurement timestamp, verbatim (undocumented
     *                              zone — stored as text, never converted); may be null
     * @param fetchedAt             when RunningAI fetched the snapshot from the connector (UTC)
     */
    @Transactional
    public GarminProfileMergeResult mergeGarminSnapshot(Integer lactateThresholdHeartRateBpmFromGarmin,
                                                         Integer lactateThresholdPaceSecondsPerKmFromGarmin,
                                                         String garminCalendarDateRaw,
                                                         Instant fetchedAt) {
        Long athleteId = athleteService.getDefaultAthlete().getId();
        var existingOpt = repository.findWithLockingByAthleteId(athleteId);
        Integer existingBpm = existingOpt.map(AthleteIntensityProfile::getLactateThresholdHeartRateBpm).orElse(null);
        Integer existingPace = existingOpt.map(AthleteIntensityProfile::getLactateThresholdPaceSecondsPerKm).orElse(null);

        Integer resolvedBpm = lactateThresholdHeartRateBpmFromGarmin != null
                ? lactateThresholdHeartRateBpmFromGarmin : existingBpm;
        Integer resolvedPace = lactateThresholdPaceSecondsPerKmFromGarmin != null
                ? lactateThresholdPaceSecondsPerKmFromGarmin : existingPace;

        boolean heartRateChanged = !Objects.equals(resolvedBpm, existingBpm);
        boolean paceChanged = !Objects.equals(resolvedPace, existingPace);
        boolean updated = heartRateChanged || paceChanged;

        AthleteIntensityProfileResponse response;
        if (updated) {
            AthleteIntensityProfile profile = existingOpt
                    .map(existing -> {
                        existing.update(resolvedBpm, resolvedPace);
                        return existing;
                    })
                    .orElseGet(() -> repository.save(new AthleteIntensityProfile(athleteId, resolvedBpm, resolvedPace)));
            response = toResponse(profile);
        } else {
            response = existingOpt.map(AthleteIntensityProfileService::toResponse)
                    .orElseGet(AthleteIntensityProfileResponse::empty);
        }

        history.save(new AthleteIntensityProfileHistory(athleteId,
                AthleteIntensityProfileHistory.Source.GARMIN, updated,
                resolvedBpm, resolvedPace, existingBpm, existingPace,
                garminCalendarDateRaw, fetchedAt, Instant.now(clock)));
        return new GarminProfileMergeResult(updated, heartRateChanged, paceChanged, response);
    }

    /**
     * Provenance of the CURRENT profile values (Phase 7B-4). {@code sourceTracked=true} only when
     * the latest {@code applied=true} ledger row matches the current profile exactly — an
     * observation row, or a value that predates the ledger, never counts (see
     * {@link LactateThresholdProvenance} for the full field/null contract).
     */
    public LactateThresholdProvenance provenance() {
        Long athleteId = athleteService.getDefaultAthlete().getId();
        var profile = repository.findByAthleteId(athleteId).orElse(null);
        Integer currentBpm = profile == null ? null : profile.getLactateThresholdHeartRateBpm();
        Integer currentPace = profile == null ? null : profile.getLactateThresholdPaceSecondsPerKm();

        var lastApplied = history.findFirstByAthleteIdAndAppliedTrueOrderByRecordedAtDescIdDesc(athleteId).orElse(null);
        boolean tracked = lastApplied != null && profile != null
                && Objects.equals(lastApplied.getLactateThresholdHeartRateBpm(), currentBpm)
                && Objects.equals(lastApplied.getLactateThresholdPaceSecondsPerKm(), currentPace);

        var lastGarmin = history.findFirstByAthleteIdAndSourceOrderByRecordedAtDescIdDesc(
                athleteId, AthleteIntensityProfileHistory.Source.GARMIN).orElse(null);

        return new LactateThresholdProvenance(
                currentBpm, currentPace,
                tracked,
                tracked ? lastApplied.getSource().name() : null,
                lastApplied == null ? null : lastApplied.getRecordedAt(),
                lastGarmin == null ? null : lastGarmin.getGarminCalendarDateRaw(),
                lastGarmin == null ? null : lastGarmin.getGarminFetchedAt());
    }

    private static AthleteIntensityProfileResponse toResponse(AthleteIntensityProfile profile) {
        return new AthleteIntensityProfileResponse(true, profile.getLactateThresholdHeartRateBpm(),
                profile.getLactateThresholdPaceSecondsPerKm());
    }
}
