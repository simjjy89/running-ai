package com.runningai.integration.garmin;

import com.runningai.recovery.RecoveryDailyValues;
import com.runningai.recovery.RecoverySnapshot;
import com.runningai.recovery.RecoverySnapshotService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Records and reports what Garmin recovery collection has actually achieved per day (Phase 8A).
 * <p>
 * Writes go through their own transaction ({@link Propagation#REQUIRES_NEW}) so a failure record
 * survives the failing caller's transaction. Reads are database-only: computing coverage never
 * contacts the connector or Garmin, which is what makes it safe to call before deciding whether a
 * backfill is worth running at all.
 */
@Service
@Transactional(readOnly = true)
public class GarminRecoveryCollectionService {

    private final GarminRecoveryCollectionRepository repository;
    private final RecoverySnapshotService snapshotService;

    public GarminRecoveryCollectionService(GarminRecoveryCollectionRepository repository,
                                           RecoverySnapshotService snapshotService) {
        this.repository = repository;
        this.snapshotService = snapshotService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFetched(Long athleteId, LocalDate date, Instant attemptedAt, GarminRecoveryDay day) {
        GarminRecoveryCollection collection = collectionFor(athleteId, date);
        collection.recordFetched(attemptedAt, day);
        repository.save(collection);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Long athleteId, LocalDate date, Instant attemptedAt, String failureCode) {
        GarminRecoveryCollection collection = collectionFor(athleteId, date);
        collection.recordFailure(attemptedAt, failureCode);
        repository.save(collection);
    }

    /**
     * Day-by-day coverage of {@code startDate..endDate} inclusive, oldest first, joining the stored
     * recovery values with the recorded collection outcomes. Every day in the range is reported,
     * including those with neither.
     */
    public GarminRecoveryCoverageResponse coverage(Long athleteId, LocalDate startDate, LocalDate endDate) {
        Map<LocalDate, RecoveryDailyValues> stored = new TreeMap<>();
        for (RecoverySnapshot snapshot : snapshotService.between(startDate, endDate)) {
            stored.put(snapshot.getRecoveryDate(), snapshot.values());
        }
        Map<LocalDate, GarminRecoveryCollection> collections = new TreeMap<>();
        for (GarminRecoveryCollection collection
                : repository.findByAthleteIdAndRecoveryDateBetweenOrderByRecoveryDateAsc(athleteId, startDate, endDate)) {
            collections.put(collection.getRecoveryDate(), collection);
        }

        List<GarminRecoveryCoverageResponse.DayCoverage> days = new ArrayList<>();
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            days.add(dayCoverage(date, stored.get(date), collections.get(date)));
        }
        return GarminRecoveryCoverageResponse.of(startDate, endDate, days);
    }

    private static GarminRecoveryCoverageResponse.DayCoverage dayCoverage(LocalDate date, RecoveryDailyValues values,
                                                                          GarminRecoveryCollection collection) {
        Map<String, Boolean> metrics = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (GarminRecoveryMetricGroup group : GarminRecoveryMetricGroup.ALL) {
            boolean present = values != null && group.isPresentIn(values);
            metrics.put(group.connectorName(), present);
            if (!present) {
                missing.add(group.connectorName());
            }
        }
        return new GarminRecoveryCoverageResponse.DayCoverage(
                date,
                status(values, collection, missing.isEmpty()),
                metrics,
                List.copyOf(missing),
                collection == null ? null : collection.getOutcome(),
                collection == null ? null : collection.getAttemptedAt(),
                collection == null ? null : collection.getFailureCode());
    }

    /**
     * Stored values decide the status first, the collection record only when nothing is stored: a
     * day that has values is covered no matter what a later re-fetch did, and the separate
     * {@code lastCollectionOutcome} still shows that re-fetch. The one contradictory combination -
     * no stored values but a {@code STORED} record, which means the values were removed afterwards -
     * reports as {@code NOT_COLLECTED} with the record left visible beside it.
     */
    private static GarminRecoveryCoverageStatus status(RecoveryDailyValues values,
                                                       GarminRecoveryCollection collection, boolean complete) {
        if (values != null) {
            return complete ? GarminRecoveryCoverageStatus.COMPLETE : GarminRecoveryCoverageStatus.PARTIAL;
        }
        if (collection == null) {
            return GarminRecoveryCoverageStatus.NOT_COLLECTED;
        }
        return switch (collection.getOutcome()) {
            case NO_DATA -> GarminRecoveryCoverageStatus.CONFIRMED_NO_DATA;
            case FAILED -> GarminRecoveryCoverageStatus.COLLECTION_FAILED;
            case STORED -> GarminRecoveryCoverageStatus.NOT_COLLECTED;
        };
    }

    /**
     * The row is only SAVED once it carries an outcome. Identity ids make {@code save()} insert
     * immediately, so saving a blank row here would hit the NOT NULL columns before the attempt has
     * anything to say.
     */
    private GarminRecoveryCollection collectionFor(Long athleteId, LocalDate date) {
        return repository.findByAthleteIdAndRecoveryDate(athleteId, date)
                .orElseGet(() -> new GarminRecoveryCollection(athleteId, date));
    }
}
