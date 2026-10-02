package com.runningai.recovery;

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
import java.time.LocalDate;
import java.util.Objects;

/**
 * One athlete-local day of Garmin recovery metrics ({@code garmin_recovery_daily}). Updated in place
 * on re-sync; see {@link #merge} for why a missing value never clears a stored one.
 */
@Entity
@Table(name = "garmin_recovery_daily", uniqueConstraints = {
        @UniqueConstraint(name = "uk_garmin_recovery_daily_athlete_date", columnNames = {"athlete_id", "recovery_date"})
})
@EntityListeners(AuditingEntityListener.class)
public class RecoverySnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "athlete_id", nullable = false, updatable = false)
    private Long athleteId;

    @Column(name = "recovery_date", nullable = false, updatable = false)
    private LocalDate recoveryDate;

    @Column(name = "hrv_last_night_avg_ms")
    private Double hrvLastNightAvgMs;

    @Column(name = "hrv_weekly_avg_ms")
    private Double hrvWeeklyAvgMs;

    @Column(name = "hrv_status", length = 32)
    private String hrvStatus;

    @Column(name = "sleep_duration_seconds")
    private Integer sleepDurationSeconds;

    @Column(name = "sleep_score")
    private Integer sleepScore;

    @Column(name = "resting_heart_rate_bpm")
    private Integer restingHeartRateBpm;

    @Column(name = "body_battery_highest")
    private Integer bodyBatteryHighest;

    @Column(name = "body_battery_lowest")
    private Integer bodyBatteryLowest;

    @Column(name = "body_battery_charged")
    private Integer bodyBatteryCharged;

    @Column(name = "body_battery_drained")
    private Integer bodyBatteryDrained;

    @Column(name = "stress_average")
    private Integer stressAverage;

    @Column(name = "stress_max")
    private Integer stressMax;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RecoverySnapshot() {
        // for JPA
    }

    public RecoverySnapshot(Long athleteId, LocalDate recoveryDate) {
        this.athleteId = Objects.requireNonNull(athleteId, "athleteId");
        this.recoveryDate = Objects.requireNonNull(recoveryDate, "recoveryDate");
    }

    /**
     * Applies freshly fetched values: a non-null value overwrites, a {@code null} keeps what is
     * stored. The connector reports a metric as missing both when Garmin truly has none and when
     * that one call failed, so a re-sync must never erase a reading that was already captured.
     *
     * @return true when at least one stored value changed
     */
    public boolean merge(RecoveryDailyValues v) {
        RecoveryDailyValues before = values();
        hrvLastNightAvgMs = pick(v.hrvLastNightAvgMs(), hrvLastNightAvgMs);
        hrvWeeklyAvgMs = pick(v.hrvWeeklyAvgMs(), hrvWeeklyAvgMs);
        hrvStatus = pick(v.hrvStatus(), hrvStatus);
        sleepDurationSeconds = pick(v.sleepDurationSeconds(), sleepDurationSeconds);
        sleepScore = pick(v.sleepScore(), sleepScore);
        restingHeartRateBpm = pick(v.restingHeartRateBpm(), restingHeartRateBpm);
        bodyBatteryHighest = pick(v.bodyBatteryHighest(), bodyBatteryHighest);
        bodyBatteryLowest = pick(v.bodyBatteryLowest(), bodyBatteryLowest);
        bodyBatteryCharged = pick(v.bodyBatteryCharged(), bodyBatteryCharged);
        bodyBatteryDrained = pick(v.bodyBatteryDrained(), bodyBatteryDrained);
        stressAverage = pick(v.stressAverage(), stressAverage);
        stressMax = pick(v.stressMax(), stressMax);
        return !before.equals(values());
    }

    private static <T> T pick(T fresh, T stored) {
        return fresh != null ? fresh : stored;
    }

    public RecoveryDailyValues values() {
        return new RecoveryDailyValues(hrvLastNightAvgMs, hrvWeeklyAvgMs, hrvStatus, sleepDurationSeconds,
                sleepScore, restingHeartRateBpm, bodyBatteryHighest, bodyBatteryLowest, bodyBatteryCharged,
                bodyBatteryDrained, stressAverage, stressMax);
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
