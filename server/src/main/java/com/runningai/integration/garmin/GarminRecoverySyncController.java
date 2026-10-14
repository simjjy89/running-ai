package com.runningai.integration.garmin;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    /**
     * {@code {"endDate": "YYYY-MM-DD", "days": 28, "onlyMissing": true}}; all optional (today, the
     * configured maximum, and {@code false}). {@code onlyMissing} skips days that are already
     * complete or that Garmin has already reported no data for, so a repeat backfill costs only the
     * calls that can still add something. It defaults to false, leaving the pre-Phase-8A behaviour
     * of a plain backfill exactly as it was.
     */
    @PostMapping("/backfill")
    public GarminRecoveryBackfillResponse backfill(@RequestBody(required = false) BackfillRequest request) {
        return request == null
                ? syncService.backfill(null, null, false)
                : syncService.backfill(request.endDate(), request.days(), Boolean.TRUE.equals(request.onlyMissing()));
    }

    /**
     * Database-only coverage report: which days have which recovery metrics, and - for a day with
     * none - whether Garmin was never asked, answered "no data", or could not be reached. Makes no
     * connector or Garmin call, so it is safe to run before deciding whether a backfill is worth it.
     */
    @GetMapping("/coverage")
    public GarminRecoveryCoverageResponse coverage(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Integer days) {
        return syncService.coverage(endDate, days);
    }

    public record SyncRequest(LocalDate date) {
    }

    public record BackfillRequest(LocalDate endDate, Integer days, Boolean onlyMissing) {
    }
}
