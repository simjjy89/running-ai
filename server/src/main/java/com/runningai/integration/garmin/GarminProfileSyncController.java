package com.runningai.integration.garmin;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual operational trigger for the Garmin athlete profile sync (Phase 6D). A mutation, hence
 * POST only. No authentication exists on this API, same as {@code POST /api/v1/garmin/sync} and
 * {@code POST /api/v1/workout-publish}: keep it on a private network, never expose it publicly.
 */
@RestController
@RequestMapping("/api/v1/garmin/profile-sync")
public class GarminProfileSyncController {

    private final GarminAthleteProfileSyncService syncService;

    public GarminProfileSyncController(GarminAthleteProfileSyncService syncService) {
        this.syncService = syncService;
    }

    @PostMapping
    public GarminProfileSyncResponse sync() {
        return syncService.sync();
    }
}
