package com.runningai.integration.garmin;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/garmin/sync")
public class GarminSyncController {

    private final GarminSyncOperationService operationService;
    private final GarminSyncStatusService statusService;

    public GarminSyncController(GarminSyncOperationService operationService, GarminSyncStatusService statusService) {
        this.operationService = operationService;
        this.statusService = statusService;
    }

    /** Runs exactly one incremental sync (requires the local Garmin connector). */
    @PostMapping
    public GarminSyncResponse sync() {
        return operationService.runSync();
    }

    /** Database-only checkpoint status; does not contact the connector or Garmin. */
    @GetMapping("/status")
    public GarminSyncStatusResponse status() {
        return statusService.getStatus();
    }
}
