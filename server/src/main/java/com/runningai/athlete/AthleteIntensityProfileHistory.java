package com.runningai.athlete;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One lactate-threshold profile event (Phase 7B-4), append-only: every field is set once at
 * construction and never reassigned, which is what makes the ledger a trustworthy audit trail.
 * {@code applied=false} rows are observations that did not change the profile (same-value Garmin
 * re-report, identical manual PUT); they prove freshness, never origin.
 */
@Entity
@Table(name = "athlete_intensity_profile_history")
public class AthleteIntensityProfileHistory {

    public enum Source { MANUAL, GARMIN }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "athlete_id", nullable = false, updatable = false)
    private Long athleteId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16, updatable = false)
    private Source source;

    @Column(name = "applied", nullable = false, updatable = false)
    private boolean applied;

    @Column(name = "lactate_threshold_heart_rate_bpm", updatable = false)
    private Integer lactateThresholdHeartRateBpm;

    @Column(name = "lactate_threshold_pace_seconds_per_km", updatable = false)
    private Integer lactateThresholdPaceSecondsPerKm;

    @Column(name = "previous_heart_rate_bpm", updatable = false)
    private Integer previousHeartRateBpm;

    @Column(name = "previous_pace_seconds_per_km", updatable = false)
    private Integer previousPaceSecondsPerKm;

    /** Garmin's own measurement timestamp, VERBATIM (no documented zone - never guess-converted). */
    @Column(name = "garmin_calendar_date_raw", length = 40, updatable = false)
    private String garminCalendarDateRaw;

    @Column(name = "garmin_fetched_at", updatable = false)
    private Instant garminFetchedAt;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    protected AthleteIntensityProfileHistory() {
        // for JPA
    }

    public AthleteIntensityProfileHistory(Long athleteId, Source source, boolean applied,
                                          Integer lactateThresholdHeartRateBpm,
                                          Integer lactateThresholdPaceSecondsPerKm,
                                          Integer previousHeartRateBpm, Integer previousPaceSecondsPerKm,
                                          String garminCalendarDateRaw, Instant garminFetchedAt,
                                          Instant recordedAt) {
        this.athleteId = athleteId;
        this.source = source;
        this.applied = applied;
        this.lactateThresholdHeartRateBpm = lactateThresholdHeartRateBpm;
        this.lactateThresholdPaceSecondsPerKm = lactateThresholdPaceSecondsPerKm;
        this.previousHeartRateBpm = previousHeartRateBpm;
        this.previousPaceSecondsPerKm = previousPaceSecondsPerKm;
        this.garminCalendarDateRaw = garminCalendarDateRaw;
        this.garminFetchedAt = garminFetchedAt;
        this.recordedAt = recordedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getAthleteId() {
        return athleteId;
    }

    public Source getSource() {
        return source;
    }

    public boolean isApplied() {
        return applied;
    }

    public Integer getLactateThresholdHeartRateBpm() {
        return lactateThresholdHeartRateBpm;
    }

    public Integer getLactateThresholdPaceSecondsPerKm() {
        return lactateThresholdPaceSecondsPerKm;
    }

    public Integer getPreviousHeartRateBpm() {
        return previousHeartRateBpm;
    }

    public Integer getPreviousPaceSecondsPerKm() {
        return previousPaceSecondsPerKm;
    }

    public String getGarminCalendarDateRaw() {
        return garminCalendarDateRaw;
    }

    public Instant getGarminFetchedAt() {
        return garminFetchedAt;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }
}
