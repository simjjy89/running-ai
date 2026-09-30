package com.runningai.integration.garmin;

import com.runningai.athlete.AthleteService;
import org.springframework.stereotype.Service;

/** Reads the current sync checkpoint from the database only; never touches the connector or Garmin. */
@Service
public class GarminSyncStatusService {

    private final GarminSyncStateService syncStateService;
    private final AthleteService athleteService;

    public GarminSyncStatusService(GarminSyncStateService syncStateService, AthleteService athleteService) {
        this.syncStateService = syncStateService;
        this.athleteService = athleteService;
    }

    public GarminSyncStatusResponse getStatus() {
        Long athleteId = athleteService.getDefaultAthlete().getId();
        return syncStateService.find(athleteId)
                .map(s -> new GarminSyncStatusResponse(true, s.getHighWaterStartedAt(), s.getLastSuccessfulSyncAt()))
                .orElseGet(() -> new GarminSyncStatusResponse(false, null, null));
    }
}
