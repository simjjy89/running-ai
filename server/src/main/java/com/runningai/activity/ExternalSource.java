package com.runningai.activity;

/**
 * Where an activity record originated. Together with the source's own id this
 * identifies an activity uniquely across repeated ingestions.
 */
public enum ExternalSource {
    GARMIN,
    INTERVALS_ICU,
    /** The athlete's own Strava data export (Phase 7E-2); the id is the Strava activity id. */
    STRAVA,
    MANUAL
}
