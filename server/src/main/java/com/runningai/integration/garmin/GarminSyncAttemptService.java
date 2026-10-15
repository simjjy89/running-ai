package com.runningai.integration.garmin;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Records the outcome of every Garmin incremental sync attempt (Phase 8A).
 * <p>
 * Writes run in their own transaction ({@link Propagation#REQUIRES_NEW}) because the record of a
 * failure must survive whatever the failing caller does with its own transaction. Recording is
 * strictly a side effect: it never changes what is fetched, and it never touches
 * {@link GarminSyncState} - a failed attempt must stay unable to move the checkpoint.
 */
@Service
@Transactional(readOnly = true)
public class GarminSyncAttemptService {

    private final GarminSyncAttemptRepository repository;

    public GarminSyncAttemptService(GarminSyncAttemptRepository repository) {
        this.repository = repository;
    }

    public Optional<GarminSyncAttempt> find(Long athleteId) {
        return repository.findByAthleteId(athleteId);
    }

    /**
     * Marks an attempt as in flight, before the connector is called (Phase 8B). Its own
     * transaction, like every other write here, so the row is visible - and survives - whatever
     * the sync itself goes on to do.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordStarted(Long athleteId, Instant attemptedAt) {
        GarminSyncAttempt attempt = attemptFor(athleteId);
        attempt.recordStarted(attemptedAt);
        repository.save(attempt);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordCompleted(Long athleteId, Instant attemptedAt, GarminIncrementalSyncResult result) {
        GarminSyncAttempt attempt = attemptFor(athleteId);
        attempt.recordCompleted(attemptedAt, result);
        repository.save(attempt);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Long athleteId, Instant attemptedAt, String failureCode) {
        GarminSyncAttempt attempt = attemptFor(athleteId);
        attempt.recordFailure(attemptedAt, failureCode);
        repository.save(attempt);
    }

    /**
     * Converts every row still claiming to be in flight into {@code INTERRUPTED} (Phase 8B) and
     * reports how many there were. Called once at startup: inside a running JVM a {@code STARTED}
     * row means a sync really is running, and only a restart proves otherwise.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int markInterrupted(String failureCode) {
        List<GarminSyncAttempt> stale = repository.findByOutcome(GarminSyncAttemptOutcome.STARTED);
        stale.forEach(attempt -> attempt.recordInterrupted(failureCode));
        repository.saveAll(stale);
        return stale.size();
    }

    /**
     * The row is only SAVED once it carries an outcome. Identity ids make {@code save()} insert
     * immediately, so saving a blank row here would hit the NOT NULL columns before the attempt has
     * anything to say.
     */
    private GarminSyncAttempt attemptFor(Long athleteId) {
        return repository.findByAthleteId(athleteId).orElseGet(() -> new GarminSyncAttempt(athleteId));
    }
}
