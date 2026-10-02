package com.runningai.integration.garmin;

import com.runningai.athlete.AthleteService;
import com.runningai.common.exception.UnprocessableRequestException;
import com.runningai.recovery.RecoverySnapshotService;
import com.runningai.recovery.RecoveryUpsertResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Pulls Garmin recovery metrics for one day (or a short backfill) and stores them (Phase 6F):
 * {@link GarminRecoveryClient} (transport) -&gt; {@link GarminRecoveryMapper} (pure normalisation) -&gt;
 * {@link RecoverySnapshotService#upsert} (idempotent storage; a missing metric never clears one).
 * <p>
 * Data collection only: nothing here rates a value or touches workouts, drafts or publishing.
 * Rate-limit discipline: strictly sequential, one connector request per day, no retry anywhere,
 * and a backfill stops at the first connector failure instead of pressing on. A single in-JVM lock
 * keeps two syncs/backfills from running at once.
 */
@Service
public class GarminRecoverySyncService {

    private static final Logger log = LoggerFactory.getLogger(GarminRecoverySyncService.class);

    private final GarminRecoveryClient client;
    private final RecoverySnapshotService snapshotService;
    private final AthleteService athleteService;
    private final GarminRecoverySyncProperties properties;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();

    public GarminRecoverySyncService(GarminRecoveryClient client, RecoverySnapshotService snapshotService,
                                     AthleteService athleteService, GarminRecoverySyncProperties properties,
                                     Clock clock) {
        this.client = client;
        this.snapshotService = snapshotService;
        this.athleteService = athleteService;
        this.properties = properties;
        this.clock = clock;
    }

    /** Syncs one day ({@code null} = the athlete's today). */
    public GarminRecoverySyncResponse syncDay(LocalDate requested) {
        LocalDate date = resolve(requested);
        lockOrReject();
        try {
            return fetchAndStore(date);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Syncs {@code days} days ending on {@code endDate} ({@code null} = today), newest first, so the
     * most recent - and most decision-relevant - days are stored even when the run is cut short.
     * If the very first day already fails, the connector failure is thrown as-is (HTTP error);
     * otherwise the partial result is returned with {@code completed=false}.
     */
    public GarminRecoveryBackfillResponse backfill(LocalDate requestedEnd, Integer requestedDays) {
        LocalDate endDate = resolve(requestedEnd);
        int days = requestedDays == null ? properties.maxBackfillDays() : requestedDays;
        if (days < 1 || days > properties.maxBackfillDays()) {
            throw new UnprocessableRequestException("INVALID_BACKFILL_DAYS",
                    "days must be between 1 and " + properties.maxBackfillDays());
        }
        LocalDate startDate = endDate.minusDays(days - 1L);
        lockOrReject();
        try {
            log.info("Garmin recovery backfill started: {} .. {} ({} days)", startDate, endDate, days);
            List<GarminRecoverySyncResponse> results = new ArrayList<>();
            for (LocalDate date = endDate; !date.isBefore(startDate); date = date.minusDays(1)) {
                if (!results.isEmpty()) {
                    pause(properties.backfillDelay());
                }
                try {
                    results.add(fetchAndStore(date));
                } catch (GarminConnectorException e) {
                    if (results.isEmpty()) {
                        throw e;
                    }
                    log.warn("Garmin recovery backfill stopped at {}: {}", date, e.getReason());
                    return response(startDate, endDate, days, results, date, e.getReason().name());
                }
            }
            log.info("Garmin recovery backfill completed: {} days, {} updated", results.size(),
                    results.stream().filter(GarminRecoverySyncResponse::updated).count());
            return response(startDate, endDate, days, results, null, null);
        } finally {
            lock.unlock();
        }
    }

    private GarminRecoverySyncResponse fetchAndStore(LocalDate date) {
        GarminRecoveryDay day = GarminRecoveryMapper.map(client.fetchDay(date), date);
        RecoveryUpsertResult stored = snapshotService.upsert(date, day.values());
        log.info("Garmin recovery sync {}: {} available={} unavailable={}", date,
                stored.updated() ? "UPDATED" : "UNCHANGED", day.availableMetrics(), day.unavailableMetrics());
        return new GarminRecoverySyncResponse(date, stored.updated(), day.availableMetrics(), day.unavailableMetrics());
    }

    private static GarminRecoveryBackfillResponse response(LocalDate startDate, LocalDate endDate, int days,
                                                           List<GarminRecoverySyncResponse> results,
                                                           LocalDate stoppedAt, String stoppedReason) {
        int updated = (int) results.stream().filter(GarminRecoverySyncResponse::updated).count();
        return new GarminRecoveryBackfillResponse(startDate, endDate, days, results.size(), updated,
                stoppedAt == null, stoppedAt, stoppedReason, List.copyOf(results));
    }

    private LocalDate resolve(LocalDate requested) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(athleteService.getDefaultAthlete().getTimezone())));
        if (requested == null) {
            return today;
        }
        if (requested.isAfter(today)) {
            throw new UnprocessableRequestException("RECOVERY_DATE_IN_FUTURE",
                    "Recovery data cannot be synced for a future date (" + requested + ")");
        }
        return requested;
    }

    private void lockOrReject() {
        if (!lock.tryLock()) {
            log.info("Garmin recovery sync request rejected: another recovery sync is running");
            throw new GarminRecoverySyncAlreadyRunningException();
        }
    }

    private static void pause(Duration delay) {
        if (delay.isZero() || delay.isNegative()) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Garmin recovery backfill interrupted", e);
        }
    }
}
