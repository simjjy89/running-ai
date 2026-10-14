package com.runningai.integration.garmin;

/**
 * What the last Garmin recovery collection attempt found for one day (Phase 8A). Observability only:
 * no recovery value and no baseline is ever derived from this.
 */
public enum GarminRecoveryCollectionOutcome {

    /** The call succeeded and at least one metric carried a value, so a recovery row exists. */
    STORED,

    /**
     * The call succeeded and Garmin returned no usable value for any metric. There is deliberately
     * no recovery row for such a day - this outcome is what distinguishes that from "never fetched".
     */
    NO_DATA,

    /** The call itself failed; nothing was returned, so the per-metric outcomes are unknown. */
    FAILED
}
