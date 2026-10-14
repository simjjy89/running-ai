package com.runningai.integration.garmin;

import com.runningai.athlete.AthleteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Operator-triggered Garmin sync with a single-flight guard: at most one incremental
 * sync runs at a time in this JVM. A concurrent request fails immediately with
 * {@link GarminSyncAlreadyRunningException} instead of queueing or starting a second
 * Garmin sync. The guard is in-memory (single-instance deployment); there is no
 * distributed lock.
 * <p>
 * Every attempt that actually starts is recorded through {@link GarminSyncAttemptService}
 * (Phase 8A), success or failure, so the database can distinguish "Garmin had nothing new" from
 * "no sync has run" from "the sync failed". A rejected concurrent request is deliberately NOT
 * recorded: it never reached Garmin, so it is not a collection attempt. Recording never changes
 * the outcome - a failure to record is logged and swallowed, and the original exception always
 * propagates unchanged.
 */
@Service
public class GarminSyncOperationService {

    private static final Logger log = LoggerFactory.getLogger(GarminSyncOperationService.class);

    private final GarminIncrementalSyncService incrementalSyncService;
    private final GarminSyncStatusService statusService;
    private final GarminSyncAttemptService attemptService;
    private final AthleteService athleteService;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();

    public GarminSyncOperationService(GarminIncrementalSyncService incrementalSyncService,
                                      GarminSyncStatusService statusService,
                                      GarminSyncAttemptService attemptService,
                                      AthleteService athleteService,
                                      Clock clock) {
        this.incrementalSyncService = incrementalSyncService;
        this.statusService = statusService;
        this.attemptService = attemptService;
        this.athleteService = athleteService;
        this.clock = clock;
    }

    public GarminSyncResponse runSync() {
        if (!lock.tryLock()) {
            log.info("Garmin sync request rejected: another sync is running");
            throw new GarminSyncAlreadyRunningException();
        }
        Long athleteId = athleteService.getDefaultAthlete().getId();
        Instant attemptedAt = clock.instant();
        try {
            GarminIncrementalSyncResult result = incrementalSyncService.syncIncremental();
            record(() -> attemptService.recordCompleted(athleteId, attemptedAt, result));
            return GarminSyncResponse.of(result, statusService.getStatus().lastSuccessfulSyncAt());
        } catch (RuntimeException e) {
            String failureCode = GarminCollectionFailureCode.of(e);
            record(() -> attemptService.recordFailure(athleteId, attemptedAt, failureCode));
            throw e;
        } finally {
            lock.unlock();
        }
    }

    /** Observability must never be the reason a sync result is lost or an exception is replaced. */
    private static void record(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("Garmin sync attempt could not be recorded: {}", e.getClass().getSimpleName());
        }
    }
}
