package com.runningai.integration.garmin;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Manual triggers for the Garmin recovery sync (Phase 6F). No authentication, same as the other
 * {@code /api/v1/garmin/*} triggers: keep it on a private network.
 */
@RestController
@RequestMapping("/api/v1/garmin/recovery-sync")
public class GarminRecoverySyncController {

    private final GarminRecoverySyncService syncService;

    public GarminRecoverySyncController(GarminRecoverySyncService syncService) {
        this.syncService = syncService;
    }

    /** {@code {"date": "YYYY-MM-DD"}}; the body or date may be omitted (= the athlete's today). */
    @PostMapping
    public GarminRecoverySyncResponse sync(@RequestBody(required = false) SyncRequest request) {
        return syncService.syncDay(request == null ? null : request.date());
    }

    /** {@code {"endDate": "YYYY-MM-DD", "days": 28}}; both optional (today, the configured maximum). */
    @PostMapping("/backfill")
    public GarminRecoveryBackfillResponse backfill(@RequestBody(required = false) BackfillRequest request) {
        return request == null
                ? syncService.backfill(null, null)
                : syncService.backfill(request.endDate(), request.days());
    }

    public record SyncRequest(LocalDate date) {
    }

    public record BackfillRequest(LocalDate endDate, Integer days) {
    }
}
