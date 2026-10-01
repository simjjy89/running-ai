package com.runningai.integration.garmin;

import com.fasterxml.jackson.databind.JsonNode;
import com.runningai.athlete.AthleteIntensityProfileService;
import com.runningai.athlete.GarminProfileMergeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Pulls the latest Garmin running lactate-threshold snapshot and merges whatever valid metric(s)
 * it contains into the athlete's intensity profile (Phase 6D). Reuses the verified pipeline
 * pieces unchanged: {@link GarminLactateThresholdSource} (transport) -&gt;
 * {@link GarminLactateThresholdMapper} (pure normalisation) -&gt;
 * {@link AthleteIntensityProfileService#mergeGarminSnapshot} (storage policy: partial/missing
 * Garmin data never clears an existing value; unchanged data never writes).
 * <p>
 * A connector failure propagates as {@link GarminConnectorException} before any merge is
 * attempted, so the existing profile is always left exactly as-is on failure - the "stale
 * fallback" the Phase 6D work order requires. This service never touches
 * {@code WorkoutIntensityTargetService} or the Intervals publishing path; it only ever writes to
 * {@code athlete_intensity_profile}, which the targeting/publishing path re-reads on every call.
 */
@Service
public class GarminAthleteProfileSyncService {

    private static final Logger log = LoggerFactory.getLogger(GarminAthleteProfileSyncService.class);

    private final GarminLactateThresholdSource source;
    private final AthleteIntensityProfileService profileService;

    public GarminAthleteProfileSyncService(GarminLactateThresholdSource source,
                                            AthleteIntensityProfileService profileService) {
        this.source = source;
        this.profileService = profileService;
    }

    public GarminProfileSyncResponse sync() {
        log.info("Garmin athlete profile sync started");
        JsonNode raw = source.fetchLatest();
        GarminLactateThresholdSnapshot snapshot = GarminLactateThresholdMapper.map(raw);

        if (snapshot.lactateThresholdHeartRateBpm() == null) {
            log.warn("Garmin athlete profile sync: lactate threshold heart rate missing or malformed in the latest snapshot");
        }
        if (snapshot.lactateThresholdPaceSecondsPerKm() == null) {
            log.warn("Garmin athlete profile sync: lactate threshold pace missing or malformed in the latest snapshot");
        }

        GarminProfileMergeResult merged = profileService.mergeGarminSnapshot(
                snapshot.lactateThresholdHeartRateBpm(), snapshot.lactateThresholdPaceSecondsPerKm());

        if (merged.updated()) {
            log.info("Garmin athlete profile sync completed: UPDATED heartRateChanged={} paceChanged={}",
                    merged.heartRateChanged(), merged.paceChanged());
        } else {
            log.info("Garmin athlete profile sync completed: UNCHANGED");
        }

        return new GarminProfileSyncResponse(merged.updated(), merged.profile().lactateThresholdHeartRateBpm(),
                merged.profile().lactateThresholdPaceSecondsPerKm());
    }
}
