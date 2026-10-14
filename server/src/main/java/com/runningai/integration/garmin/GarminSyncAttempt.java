package com.runningai.integration.garmin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.Objects;

/**
 * The outcome of the last Garmin incremental sync attempt for one athlete (Phase 8A).
 * <p>
 * This exists because {@link GarminSyncState} records only success, which left "Garmin had nothing
 * new", "no sync has run" and "the sync failed" indistinguishable in the database. It is written
 * after every attempt - including a failed one - and is read by nothing that decides what to fetch.
 */
@Entity
@Table(name = "garmin_sync_attempt", uniqueConstraints = {
        @UniqueConstraint(name = "uk_garmin_sync_attempt_athlete", columnNames = "athlete_id")
})
@EntityListeners(AuditingEntityListener.class)
public class GarminSyncAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "athlete_id", nullable = false)
    private Long athleteId;

    @Column(name = "attempted_at", nullable = false)
    private Instant attemptedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 16)
    private GarminSyncAttemptOutcome outcome;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "fetched", nullable = false)
    private int fetched;

    @Column(name = "created", nullable = false)
    private int created;

    @Column(name = "updated", nullable = false)
    private int updated;

    @Column(name = "skipped", nullable = false)
    private int skipped;

    @Column(name = "failed", nullable = false)
    private int failed;

    @Column(name = "pages_fetched", nullable = false)
    private int pagesFetched;

    @Column(name = "checkpoint_advanced", nullable = false)
    private boolean checkpointAdvanced;

    @Column(name = "consecutive_failure_count", nullable = false)
    private int consecutiveFailureCount;

    @Column(name = "last_failure_at")
    private Instant lastFailureAt;

    @Column(name = "last_failure_code", length = 64)
    private String lastFailureCode;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected GarminSyncAttempt() {
        // for JPA
    }

    GarminSyncAttempt(Long athleteId) {
        this.athleteId = Objects.requireNonNull(athleteId, "athleteId");
    }

    /**
     * Records a completed run. {@code failed > 0} is a {@link GarminSyncAttemptOutcome#PARTIAL}
     * outcome, matching the sync's own rule that such a run does not advance the checkpoint.
     * Any completed run clears the consecutive-failure streak; the historical
     * {@code lastFailure*} fields are deliberately kept so "when did this last break?" stays answerable.
     */
    void recordCompleted(Instant attemptedAt, GarminIncrementalSyncResult result) {
        this.attemptedAt = attemptedAt;
        this.outcome = result.failed() > 0 ? GarminSyncAttemptOutcome.PARTIAL : GarminSyncAttemptOutcome.SUCCESS;
        this.failureCode = null;
        this.fetched = result.fetched();
        this.created = result.created();
        this.updated = result.updated();
        this.skipped = result.skipped();
        this.failed = result.failed();
        this.pagesFetched = result.pagesFetched();
        this.checkpointAdvanced = result.checkpointAdvanced();
        this.consecutiveFailureCount = 0;
    }

    /** Records a run that threw. Counts are zeroed: the run produced no result to report. */
    void recordFailure(Instant attemptedAt, String failureCode) {
        this.attemptedAt = attemptedAt;
        this.outcome = GarminSyncAttemptOutcome.FAILED;
        this.failureCode = failureCode;
        this.fetched = 0;
        this.created = 0;
        this.updated = 0;
        this.skipped = 0;
        this.failed = 0;
        this.pagesFetched = 0;
        this.checkpointAdvanced = false;
        this.consecutiveFailureCount++;
        this.lastFailureAt = attemptedAt;
        this.lastFailureCode = failureCode;
    }

    public Long getId() {
        return id;
    }

    public Long getAthleteId() {
        return athleteId;
    }

    public Instant getAttemptedAt() {
        return attemptedAt;
    }

    public GarminSyncAttemptOutcome getOutcome() {
        return outcome;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public int getFetched() {
        return fetched;
    }

    public int getCreated() {
        return created;
    }

    public int getUpdated() {
        return updated;
    }

    public int getSkipped() {
        return skipped;
    }

    public int getFailed() {
        return failed;
    }

    public int getPagesFetched() {
        return pagesFetched;
    }

    public boolean isCheckpointAdvanced() {
        return checkpointAdvanced;
    }

    public int getConsecutiveFailureCount() {
        return consecutiveFailureCount;
    }

    public Instant getLastFailureAt() {
        return lastFailureAt;
    }

    public String getLastFailureCode() {
        return lastFailureCode;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
