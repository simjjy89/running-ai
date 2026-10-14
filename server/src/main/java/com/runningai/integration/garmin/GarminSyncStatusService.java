package com.runningai.integration.garmin;

import com.runningai.athlete.AthleteService;
import com.runningai.integration.garmin.GarminSyncStatusResponse.GarminSyncAttemptStatus;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** Reads the current sync checkpoint and last attempt from the database only; never touches the connector or Garmin. */
@Service
public class GarminSyncStatusService {

    private final GarminSyncStateService syncStateService;
    private final GarminSyncAttemptService attemptService;
    private final AthleteService athleteService;

    public GarminSyncStatusService(GarminSyncStateService syncStateService,
                                   GarminSyncAttemptService attemptService,
                                   AthleteService athleteService) {
        this.syncStateService = syncStateService;
        this.attemptService = attemptService;
        this.athleteService = athleteService;
    }

    public GarminSyncStatusResponse getStatus() {
        Long athleteId = athleteService.getDefaultAthlete().getId();
        GarminSyncAttemptStatus lastAttempt = attemptService.find(athleteId)
                .map(GarminSyncAttemptStatus::of)
                .orElse(null);
        Optional<GarminSyncState> state = syncStateService.find(athleteId);
        return state
                .map(s -> new GarminSyncStatusResponse(true, s.getHighWaterStartedAt(), s.getLastSuccessfulSyncAt(),
                        lastAttempt))
                .orElseGet(() -> new GarminSyncStatusResponse(false, null, null, lastAttempt));
    }
}
