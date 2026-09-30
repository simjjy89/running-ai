package com.runningai.integration.garmin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the same incremental sync as {@code POST /api/v1/garmin/sync}, periodically.
 * Only present when {@code running-ai.garmin.scheduler.enabled=true} (default off).
 * <p>
 * It goes through {@link GarminSyncOperationService}, so it shares the single-flight guard
 * with the manual API and duplicates no sync logic. Fixed-delay: the next tick is measured
 * from the end of the previous run. Any failure ends the current tick; there is no retry
 * inside a tick, so a rate limit or auth problem is never hammered; the next regular tick
 * simply tries again. Nothing thrown here can kill the scheduler thread, and only counts
 * and error codes are logged (never activity data or credentials).
 */
@Component
@ConditionalOnProperty(prefix = "running-ai.garmin.scheduler", name = "enabled", havingValue = "true")
public class GarminSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(GarminSyncScheduler.class);

    private final GarminSyncOperationService operationService;

    public GarminSyncScheduler(GarminSyncOperationService operationService) {
        this.operationService = operationService;
    }

    @Scheduled(fixedDelayString = "${running-ai.garmin.scheduler.fixed-delay:1h}",
            initialDelayString = "${running-ai.garmin.scheduler.initial-delay:1m}")
    public void runScheduledSync() {
        try {
            GarminSyncResponse r = operationService.runSync();
            log.info("Garmin scheduled sync completed: fetched={} created={} updated={} skipped={} failed={} "
                            + "pages={} checkpointAdvanced={}",
                    r.fetched(), r.created(), r.updated(), r.skipped(), r.failed(),
                    r.pagesFetched(), r.checkpointAdvanced());
        } catch (GarminSyncAlreadyRunningException e) {
            log.info("Garmin scheduled sync SKIPPED_ALREADY_RUNNING; waiting for the next tick");
        } catch (GarminConnectorException e) {
            log.warn("Garmin scheduled sync failed: reason={} httpStatus={}; no retry until the next tick",
                    e.getReason(), e.getHttpStatus());
        } catch (GarminIncrementalSyncException e) {
            log.warn("Garmin scheduled sync failed: INCREMENTAL_WINDOW_INCOMPLETE; no retry until the next tick");
        } catch (RuntimeException e) {
            log.error("Garmin scheduled sync failed unexpectedly: {}; no retry until the next tick",
                    e.getClass().getSimpleName());
        }
    }
}
