package com.runningai.integration.garmin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the same Garmin athlete profile sync as {@code POST /api/v1/garmin/profile-sync},
 * periodically. Only present when {@code running-ai.garmin.profile-sync.scheduler.enabled=true}
 * (default off; env {@code GARMIN_PROFILE_SYNC_ENABLED}).
 * <p>
 * Deliberately independent of both {@link GarminSyncScheduler} (activity incremental sync: a
 * different Garmin endpoint and a different checkpoint, not a natural fit to bundle together) and
 * {@code WorkoutPublishingScheduler} (workout publishing never depends on Garmin profile
 * availability, and never fails because of it: a failed or skipped profile sync just leaves the
 * last saved profile in place as a stale fallback, which {@code WorkoutIntensityTargetService}
 * re-reads on every call regardless). There is no retry and no missed-run catch-up; every failure
 * is logged and ends the tick without escaping the scheduler thread.
 */
@Component
@ConditionalOnProperty(prefix = "running-ai.garmin.profile-sync.scheduler", name = "enabled", havingValue = "true")
public class GarminProfileSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(GarminProfileSyncScheduler.class);

    private final GarminAthleteProfileSyncService syncService;

    public GarminProfileSyncScheduler(GarminAthleteProfileSyncService syncService) {
        this.syncService = syncService;
    }

    @Scheduled(cron = "${running-ai.garmin.profile-sync.scheduler.cron:" + GarminProfileSyncProperties.DEFAULT_CRON + "}",
            zone = "${running-ai.garmin.profile-sync.scheduler.zone:" + GarminProfileSyncProperties.DEFAULT_ZONE + "}")
    public void runScheduledSync() {
        try {
            GarminProfileSyncResponse r = syncService.sync();
            log.info("Garmin profile sync scheduled run completed: updated={}", r.updated());
        } catch (GarminConnectorException e) {
            log.warn("Garmin profile sync scheduled run failed: reason={} httpStatus={}; "
                    + "stale profile preserved, no retry until the next tick", e.getReason(), e.getHttpStatus());
        } catch (RuntimeException e) {
            log.error("Garmin profile sync scheduled run failed unexpectedly: {}; no retry until the next tick",
                    e.getClass().getSimpleName());
        }
    }
}
