package com.runningai.athlete;

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
 * One athlete's lactate-threshold heart rate (LTHR) and threshold pace: athlete
 * state, not a per-workout value, so it is updated in place rather than
 * versioned. Both metrics are independently optional; see
 * {@link com.runningai.training.WorkoutIntensityTargetService} for how a missing
 * value falls back to a qualitative target instead of failing.
 */
@Entity
@Table(name = "athlete_intensity_profile", uniqueConstraints = {
        @UniqueConstraint(name = "uk_athlete_intensity_profile_athlete", columnNames = "athlete_id")
})
@EntityListeners(AuditingEntityListener.class)
public class AthleteIntensityProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "athlete_id", nullable = false)
    private Long athleteId;

    @Column(name = "lactate_threshold_heart_rate_bpm")
    private Integer lactateThresholdHeartRateBpm;

    @Column(name = "lactate_threshold_pace_seconds_per_km")
    private Integer lactateThresholdPaceSecondsPerKm;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AthleteIntensityProfile() {
        // for JPA
    }

    public AthleteIntensityProfile(Long athleteId, Integer lactateThresholdHeartRateBpm,
                                    Integer lactateThresholdPaceSecondsPerKm) {
        this.athleteId = Objects.requireNonNull(athleteId, "athleteId");
        applyValues(lactateThresholdHeartRateBpm, lactateThresholdPaceSecondsPerKm);
    }

    /** Complete replacement: a null value means that metric is not set (not "leave unchanged"). */
    public void update(Integer lactateThresholdHeartRateBpm, Integer lactateThresholdPaceSecondsPerKm) {
        applyValues(lactateThresholdHeartRateBpm, lactateThresholdPaceSecondsPerKm);
    }

    private void applyValues(Integer lactateThresholdHeartRateBpm, Integer lactateThresholdPaceSecondsPerKm) {
        if (lactateThresholdHeartRateBpm != null && lactateThresholdHeartRateBpm <= 0) {
            throw new IllegalArgumentException("lactateThresholdHeartRateBpm must be > 0");
        }
        if (lactateThresholdPaceSecondsPerKm != null && lactateThresholdPaceSecondsPerKm <= 0) {
            throw new IllegalArgumentException("lactateThresholdPaceSecondsPerKm must be > 0");
        }
        this.lactateThresholdHeartRateBpm = lactateThresholdHeartRateBpm;
        this.lactateThresholdPaceSecondsPerKm = lactateThresholdPaceSecondsPerKm;
    }

    public Long getId() {
        return id;
    }

    public Long getAthleteId() {
        return athleteId;
    }

    public Integer getLactateThresholdHeartRateBpm() {
        return lactateThresholdHeartRateBpm;
    }

    public Integer getLactateThresholdPaceSecondsPerKm() {
        return lactateThresholdPaceSecondsPerKm;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
