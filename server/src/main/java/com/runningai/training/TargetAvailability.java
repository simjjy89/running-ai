package com.runningai.training;

/** Whether the athlete's intensity profile supports pace and/or heart-rate targets for this prescription. */
public enum TargetAvailability {
    FULL,
    PACE_ONLY,
    HEART_RATE_ONLY,
    QUALITATIVE_ONLY
}
