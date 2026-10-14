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
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;

/**
 * The latest known collection outcome for one athlete-local recovery day (Phase 8A).
 * <p>
 * It exists to disambiguate a MISSING {@code garmin_recovery_daily} row, which can mean "never
 * fetched", "fetched and Garmin genuinely had nothing" or "the fetch failed" - three cases that need
 * three different operator responses. One row per day, replaced by each new attempt.
 */
@Entity
@Table(name = "garmin_recovery_collection", uniqueConstraints = {
        @UniqueConstraint(name = "uk_garmin_recovery_collection_athlete_date",
                columnNames = {"athlete_id", "recovery_date"})
})
@EntityListeners(AuditingEntityListener.class)
public class GarminRecoveryCollection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "athlete_id", nullable = false)
    private Long athleteId;

    @Column(name = "recovery_date", nullable = false)
    private LocalDate recoveryDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 16)
    private GarminRecoveryCollectionOutcome outcome;

    @Column(name = "attempted_at", nullable = false)
    private Instant attemptedAt;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "hrv_outcome", length = 16)
    private GarminRecoveryMetricOutcome hrvOutcome;

    @Enumerated(EnumType.STRING)
    @Column(name = "sleep_outcome", length = 16)
    private GarminRecoveryMetricOutcome sleepOutcome;

    @Enumerated(EnumType.STRING)
    @Column(name = "resting_heart_rate_outcome", length = 16)
    private GarminRecoveryMetricOutcome restingHeartRateOutcome;

    @Enumerated(EnumType.STRING)
    @Column(name = "body_battery_outcome", length = 16)
    private GarminRecoveryMetricOutcome bodyBatteryOutcome;

    @Enumerated(EnumType.STRING)
    @Column(name = "stress_outcome", length = 16)
    private GarminRecoveryMetricOutcome stressOutcome;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected GarminRecoveryCollection() {
        // for JPA
    }

    GarminRecoveryCollection(Long athleteId, LocalDate recoveryDate) {
        this.athleteId = Objects.requireNonNull(athleteId, "athleteId");
        this.recoveryDate = Objects.requireNonNull(recoveryDate, "recoveryDate");
    }

    /**
     * Records a successful call. {@code STORED} vs {@code NO_DATA} follows the same rule
     * {@link com.runningai.recovery.RecoverySnapshotService} uses to decide whether a row exists at
     * all: at least one metric delivered a value.
     */
    void recordFetched(Instant attemptedAt, GarminRecoveryDay day) {
        this.attemptedAt = attemptedAt;
        this.outcome = day.availableMetrics().isEmpty()
                ? GarminRecoveryCollectionOutcome.NO_DATA
                : GarminRecoveryCollectionOutcome.STORED;
        this.failureCode = null;
        Map<String, String> unavailable = day.unavailableMetrics();
        this.hrvOutcome = metricOutcome(day, unavailable, "hrv");
        this.sleepOutcome = metricOutcome(day, unavailable, "sleep");
        this.restingHeartRateOutcome = metricOutcome(day, unavailable, "restingHeartRate");
        this.bodyBatteryOutcome = metricOutcome(day, unavailable, "bodyBattery");
        this.stressOutcome = metricOutcome(day, unavailable, "stress");
    }

    /** Records a failed call: nothing was returned, so every per-metric outcome is cleared to unknown. */
    void recordFailure(Instant attemptedAt, String failureCode) {
        this.attemptedAt = attemptedAt;
        this.outcome = GarminRecoveryCollectionOutcome.FAILED;
        this.failureCode = failureCode;
        this.hrvOutcome = null;
        this.sleepOutcome = null;
        this.restingHeartRateOutcome = null;
        this.bodyBatteryOutcome = null;
        this.stressOutcome = null;
    }

    private static GarminRecoveryMetricOutcome metricOutcome(GarminRecoveryDay day,
                                                             Map<String, String> unavailable, String metric) {
        if (day.availableMetrics().contains(metric)) {
            return GarminRecoveryMetricOutcome.AVAILABLE;
        }
        return GarminRecoveryMetricOutcome.fromUnavailableReason(unavailable.get(metric));
    }

    public Long getId() {
        return id;
    }

    public Long getAthleteId() {
        return athleteId;
    }

    public LocalDate getRecoveryDate() {
        return recoveryDate;
    }

    public GarminRecoveryCollectionOutcome getOutcome() {
        return outcome;
    }

    public Instant getAttemptedAt() {
        return attemptedAt;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public GarminRecoveryMetricOutcome getHrvOutcome() {
        return hrvOutcome;
    }

    public GarminRecoveryMetricOutcome getSleepOutcome() {
        return sleepOutcome;
    }

    public GarminRecoveryMetricOutcome getRestingHeartRateOutcome() {
        return restingHeartRateOutcome;
    }

    public GarminRecoveryMetricOutcome getBodyBatteryOutcome() {
        return bodyBatteryOutcome;
    }

    public GarminRecoveryMetricOutcome getStressOutcome() {
        return stressOutcome;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
