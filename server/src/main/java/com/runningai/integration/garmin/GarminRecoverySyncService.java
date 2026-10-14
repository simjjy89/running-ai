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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
 * <p>
 * Phase 8A adds two things, neither of which changes what is fetched or stored for a day that is
 * fetched. Every attempt - success, "Garmin had nothing" or failure - is recorded through
 * {@link GarminRecoveryCollectionService}, so a missing recovery row stops being ambiguous. And a
 * backfill may be asked to skip days that already hold everything Garmin can give
 * ({@code onlyMissing}), which removes pointless connector calls rather than changing any outcome.
 */
@Service
public class GarminRecoverySyncService {

    private static final Logger log = LoggerFactory.getLogger(GarminRecoverySyncService.class);

    private final GarminRecoveryClient client;
    private final RecoverySnapshotService snapshotService;
    private final GarminRecoveryCollectionService collectionService;
    private final AthleteService athleteService;
    private final GarminRecoverySyncProperties properties;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();

    public GarminRecoverySyncService(GarminRecoveryClient client, RecoverySnapshotService snapshotService,
                                     GarminRecoveryCollectionService collectionService,
                                     AthleteService athleteService, GarminRecoverySyncProperties properties,
                                     Clock clock) {
        this.client = client;
        this.snapshotService = snapshotService;
        this.collectionService = collectionService;
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
     * <p>
     * With {@code onlyMissing}, days that are already complete, or that Garmin has already reported
     * no data for, are skipped without a connector call. Days with only some metrics stored are
     * still fetched: Garmin can fill a metric in later, and the upsert never overwrites a stored
     * value with a missing one.
     */
    public GarminRecoveryBackfillResponse backfill(LocalDate requestedEnd, Integer requestedDays, boolean onlyMissing) {
        LocalDate endDate = resolve(requestedEnd);
        int days = requestedDays == null ? properties.maxBackfillDays() : requestedDays;
        if (days < 1 || days > properties.maxBackfillDays()) {
            throw new UnprocessableRequestException("INVALID_BACKFILL_DAYS",
                    "days must be between 1 and " + properties.maxBackfillDays());
        }
        LocalDate startDate = endDate.minusDays(days - 1L);
        lockOrReject();
        try {
            Set<LocalDate> targets = targetDates(startDate, endDate, onlyMissing);
            log.info("Garmin recovery backfill started: {} .. {} ({} days, onlyMissing={}, {} to fetch)",
                    startDate, endDate, days, onlyMissing, targets.size());
            List<GarminRecoverySyncResponse> results = new ArrayList<>();
            int skippedDays = 0;
            boolean fetchedAnything = false;
            for (LocalDate date = endDate; !date.isBefore(startDate); date = date.minusDays(1)) {
                if (!targets.contains(date)) {
                    skippedDays++;
                    continue;
                }
                // The pause separates two connector calls, so it follows what was actually fetched,
                // never the loop position: a run that skipped ten stored days must not also sleep.
                if (fetchedAnything) {
                    pause(properties.backfillDelay());
                }
                fetchedAnything = true;
                try {
                    results.add(fetchAndStore(date));
                } catch (GarminConnectorException e) {
                    if (results.isEmpty()) {
                        throw e;
                    }
                    log.warn("Garmin recovery backfill stopped at {}: {}", date, e.getReason());
                    return response(startDate, endDate, days, onlyMissing, skippedDays, results, date,
                            e.getReason().name());
                }
            }
            log.info("Garmin recovery backfill completed: {} days fetched, {} skipped, {} updated",
                    results.size(), skippedDays,
                    results.stream().filter(GarminRecoverySyncResponse::updated).count());
            return response(startDate, endDate, days, onlyMissing, skippedDays, results, null, null);
        } finally {
            lock.unlock();
        }
    }

    /** Database-only coverage of the {@code days} days ending on {@code endDate}; calls nothing external. */
    public GarminRecoveryCoverageResponse coverage(LocalDate requestedEnd, Integer requestedDays) {
        LocalDate endDate = resolve(requestedEnd);
        int days = requestedDays == null ? properties.maxBackfillDays() : requestedDays;
        if (days < 1 || days > properties.maxCoverageDays()) {
            throw new UnprocessableRequestException("INVALID_COVERAGE_DAYS",
                    "days must be between 1 and " + properties.maxCoverageDays());
        }
        return collectionService.coverage(athleteService.getDefaultAthlete().getId(),
                endDate.minusDays(days - 1L), endDate);
    }

    /** Every day of the window, or - when asked - only those a fetch could still improve. */
    private Set<LocalDate> targetDates(LocalDate startDate, LocalDate endDate, boolean onlyMissing) {
        Set<LocalDate> dates = new LinkedHashSet<>();
        if (onlyMissing) {
            Long athleteId = athleteService.getDefaultAthlete().getId();
            dates.addAll(collectionService.coverage(athleteId, startDate, endDate).datesWorthFetching());
            return dates;
        }
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            dates.add(date);
        }
        return dates;
    }

    private GarminRecoverySyncResponse fetchAndStore(LocalDate date) {
        Long athleteId = athleteService.getDefaultAthlete().getId();
        Instant attemptedAt = clock.instant();
        GarminRecoveryDay day;
        try {
            day = GarminRecoveryMapper.map(client.fetchDay(date), date);
        } catch (RuntimeException e) {
            String failureCode = GarminCollectionFailureCode.of(e);
            record(() -> collectionService.recordFailure(athleteId, date, attemptedAt, failureCode));
            throw e;
        }
        RecoveryUpsertResult stored = snapshotService.upsert(date, day.values());
        record(() -> collectionService.recordFetched(athleteId, date, attemptedAt, day));
        log.info("Garmin recovery sync {}: {} available={} unavailable={}", date,
                stored.updated() ? "UPDATED" : "UNCHANGED", day.availableMetrics(), day.unavailableMetrics());
        return new GarminRecoverySyncResponse(date, stored.updated(), day.availableMetrics(), day.unavailableMetrics());
    }

    /** Observability must never be the reason a fetched day is lost or an exception is replaced. */
    private static void record(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("Garmin recovery collection outcome could not be recorded: {}", e.getClass().getSimpleName());
        }
    }

    private static GarminRecoveryBackfillResponse response(LocalDate startDate, LocalDate endDate, int days,
                                                           boolean onlyMissing, int skippedDays,
                                                           List<GarminRecoverySyncResponse> results,
                                                           LocalDate stoppedAt, String stoppedReason) {
        int updated = (int) results.stream().filter(GarminRecoverySyncResponse::updated).count();
        return new GarminRecoveryBackfillResponse(startDate, endDate, days, onlyMissing, results.size(), skippedDays,
                updated, stoppedAt == null, stoppedAt, stoppedReason, List.copyOf(results));
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
