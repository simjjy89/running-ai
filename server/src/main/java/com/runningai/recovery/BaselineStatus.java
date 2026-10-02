package com.runningai.recovery;

/** Whether a personal baseline could be computed. A data-quality fact, never a rating. */
public enum BaselineStatus {
    /** At least the minimum number of valid days exist in the baseline window. */
    AVAILABLE,
    /** Too few valid days: baseline, difference and difference percentage are null. */
    INSUFFICIENT_DATA
}
