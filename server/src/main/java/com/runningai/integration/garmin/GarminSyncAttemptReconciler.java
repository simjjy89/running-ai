package com.runningai.integration.garmin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Closes out sync attempts that a dead JVM left open (Phase 8B).
 * <p>
 * {@code GarminSyncOperationService} writes a {@code STARTED} row before calling the connector and
 * overwrites it with the real outcome afterwards. That overwrite cannot happen if the process is
 * killed in between - a watchdog restart, this machine going to sleep, a power cut - so a
 * {@code STARTED} row found at startup is proof that an attempt was interrupted rather than
 * finished. Before Phase 8B that attempt left no trace at all and the ledger went on describing
 * whatever had run before it.
 * <p>
 * The conversion is deliberately not a failure. Garmin refused nothing; this machine stopped. It
 * therefore does not touch either streak counter and cannot push
 * {@link GarminSyncCircuitBreaker} towards blocking - otherwise every watchdog restart would be a
 * step towards stopping collection. Nothing is lost either way: rows already committed stay, the
 * checkpoint was never advanced, and the next run re-covers the window through the 7-day overlap.
 * <p>
 * Runs on {@code ApplicationReadyEvent}, after Flyway and after the scheduler would first be able
 * to fire, and never contacts Garmin.
 */
@Component
public class GarminSyncAttemptReconciler {

    /** Stored in {@code failure_code} so the ledger says WHY the attempt has no outcome. */
    static final String INTERRUPTED_CODE = "PROCESS_INTERRUPTED";

    private static final Logger log = LoggerFactory.getLogger(GarminSyncAttemptReconciler.class);

    private final GarminSyncAttemptService attemptService;

    public GarminSyncAttemptReconciler(GarminSyncAttemptService attemptService) {
        this.attemptService = attemptService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileInterruptedAttempts() {
        try {
            int reconciled = attemptService.markInterrupted(INTERRUPTED_CODE);
            if (reconciled > 0) {
                log.warn("Garmin sync attempts left in flight by a previous process: {} marked INTERRUPTED; "
                        + "no data was lost (the checkpoint was not advanced)", reconciled);
            }
        } catch (RuntimeException e) {
            // Bookkeeping must never stop the application from starting.
            log.warn("Could not reconcile interrupted Garmin sync attempts: {}", e.getClass().getSimpleName());
        }
    }
}
