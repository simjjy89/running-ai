package com.runningai.integration.garmin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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
 * One athlete's Garmin incremental sync checkpoint: the high-water mark (newest
 * activity start time processed so far) and when a sync last completed
 * successfully. See {@link GarminIncrementalSyncService} for how it advances.
 */
@Entity
@Table(name = "garmin_sync_state", uniqueConstraints = {
        @UniqueConstraint(name = "uk_garmin_sync_state_athlete", columnNames = "athlete_id")
})
@EntityListeners(AuditingEntityListener.class)
public class GarminSyncState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "athlete_id", nullable = false)
    private Long athleteId;

    @Column(name = "high_water_started_at", nullable = false)
    private Instant highWaterStartedAt;

    @Column(name = "last_successful_sync_at", nullable = false)
    private Instant lastSuccessfulSyncAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected GarminSyncState() {
        // for JPA
    }

    public GarminSyncState(Long athleteId, Instant highWaterStartedAt, Instant lastSuccessfulSyncAt) {
        this.athleteId = Objects.requireNonNull(athleteId, "athleteId");
        this.highWaterStartedAt = Objects.requireNonNull(highWaterStartedAt, "highWaterStartedAt");
        this.lastSuccessfulSyncAt = Objects.requireNonNull(lastSuccessfulSyncAt, "lastSuccessfulSyncAt");
    }

    /**
     * Moves the checkpoint forward after a successful incremental sync. The
     * high-water mark only ever moves forward in time; {@code lastSuccessfulSyncAt}
     * always reflects this sync, even when the high-water mark itself did not move.
     */
    void advance(Instant candidateHighWater, Instant syncedAt) {
        if (candidateHighWater.isAfter(this.highWaterStartedAt)) {
            this.highWaterStartedAt = candidateHighWater;
        }
        this.lastSuccessfulSyncAt = syncedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getAthleteId() {
        return athleteId;
    }

    public Instant getHighWaterStartedAt() {
        return highWaterStartedAt;
    }

    public Instant getLastSuccessfulSyncAt() {
        return lastSuccessfulSyncAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
