package com.runningai.activity;

/**
 * Where an activity record originated. Together with the source's own id this
 * identifies an activity uniquely across repeated ingestions.
 */
public enum ExternalSource {
    GARMIN,
    INTERVALS_ICU,
    MANUAL
}
