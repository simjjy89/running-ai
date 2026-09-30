package com.runningai.training;

/** Descriptive direction of the 7-day load versus the previous 7 days; not a safety classification. */
public enum LoadTrend {
    INCREASING,
    STABLE,
    DECREASING,
    /** Undefined: no previous-week baseline. */
    UNKNOWN
}
