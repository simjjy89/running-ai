package com.runningai.integration.garmin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Operator-triggered Garmin sync with a single-flight guard: at most one incremental
 * sync runs at a time in this JVM. A concurrent request fails immediately with
 * {@link GarminSyncAlreadyRunningException} instead of queueing or starting a second
 * Garmin sync. The guard is in-memory (single-instance deployment); there is no
 * distributed lock.
 */
@Service
public class GarminSyncOperationService {

    private static final Logger log = LoggerFactory.getLogger(GarminSyncOperationService.class);

    private final GarminIncrementalSyncService incrementalSyncService;
    private final GarminSyncStatusService statusService;
    private final ReentrantLock lock = new ReentrantLock();

    public GarminSyncOperationService(GarminIncrementalSyncService incrementalSyncService,
                                      GarminSyncStatusService statusService) {
        this.incrementalSyncService = incrementalSyncService;
        this.statusService = statusService;
    }

    public GarminSyncResponse runSync() {
        if (!lock.tryLock()) {
            log.info("Garmin sync request rejected: another sync is running");
            throw new GarminSyncAlreadyRunningException();
        }
        try {
            GarminIncrementalSyncResult result = incrementalSyncService.syncIncremental();
            return GarminSyncResponse.of(result, statusService.getStatus().lastSuccessfulSyncAt());
        } finally {
            lock.unlock();
        }
    }
}
