package com.runningai.activity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.Objects;

/**
 * A single workout session (outdoor run, treadmill run, indoor cycling, ...).
 * <p>
 * The database id is internal; the originating system's id is kept separately in
 * {@code externalSource} + {@code externalId}, which is unique so that repeated
 * Garmin / Intervals.icu ingestion cannot create duplicates.
 */
@Entity
@Table(name = "activity",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_activity_external", columnNames = {"external_source", "external_id"})
        },
        indexes = {
                @Index(name = "ix_activity_athlete_started", columnList = "athlete_id, started_at")
        })
@EntityListeners(AuditingEntityListener.class)
public class Activity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "athlete_id", nullable = false)
    private Long athleteId;

    @Enumerated(EnumType.STRING)
    @Column(name = "external_source", nullable = false, length = 32)
    private ExternalSource externalSource;

    @Column(name = "external_id", nullable = false, length = 100)
    private String externalId;

    @Enumerated(EnumType.STRING)
    @Column(name = "activity_type", nullable = false, length = 32)
    private ActivityType activityType;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "duration_seconds", nullable = false)
    private int durationSeconds;

    @Column(name = "distance_meters")
    private Double distanceMeters;

    @Column(name = "average_heart_rate")
    private Integer averageHeartRate;

    @Column(name = "max_heart_rate")
    private Integer maxHeartRate;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Activity() {
        // for JPA
    }

    public Activity(Long athleteId,
                    ExternalSource externalSource,
                    String externalId,
                    ActivityType activityType,
                    Instant startedAt,
                    int durationSeconds,
                    Double distanceMeters,
                    Integer averageHeartRate,
                    Integer maxHeartRate) {
        this.athleteId = Objects.requireNonNull(athleteId, "athleteId");
        this.externalSource = Objects.requireNonNull(externalSource, "externalSource");
        this.externalId = Objects.requireNonNull(externalId, "externalId");
        this.activityType = Objects.requireNonNull(activityType, "activityType");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        if (durationSeconds < 0) {
            throw new IllegalArgumentException("durationSeconds must be >= 0");
        }
        this.durationSeconds = durationSeconds;
        this.distanceMeters = distanceMeters;
        this.averageHeartRate = averageHeartRate;
        this.maxHeartRate = maxHeartRate;
    }

    public Long getId() {
        return id;
    }

    public Long getAthleteId() {
        return athleteId;
    }

    public ExternalSource getExternalSource() {
        return externalSource;
    }

    public String getExternalId() {
        return externalId;
    }

    public ActivityType getActivityType() {
        return activityType;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public int getDurationSeconds() {
        return durationSeconds;
    }

    public Double getDistanceMeters() {
        return distanceMeters;
    }

    public Integer getAverageHeartRate() {
        return averageHeartRate;
    }

    public Integer getMaxHeartRate() {
        return maxHeartRate;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
