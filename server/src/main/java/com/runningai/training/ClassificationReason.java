package com.runningai.training;

/** Why a day received its {@link SessionClassification}. */
public enum ClassificationReason {
    /** A single run of at least the configured long-run duration. */
    DURATION_THRESHOLD,
    /** At least one run, none reaching the long-run duration. */
    RUNNING_ACTIVITY,
    /** Cycling only, no run. */
    CYCLING_ONLY,
    /** No supported activity, or zero load. */
    NO_ACTIVITY
}
