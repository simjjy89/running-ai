package com.runningai.integration.garmin;

/**
 * What Garmin recovery collection has achieved for one day (Phase 8A) - a statement about the DATA
 * and the COLLECTION, never about the athlete's recovery.
 * <p>
 * The point of the enum is that the three ways a day can have no stored values are kept apart:
 * {@link #CONFIRMED_NO_DATA} (Garmin was asked and genuinely has nothing - fetching again cannot
 * help), {@link #COLLECTION_FAILED} (the call broke - worth another try) and {@link #NOT_COLLECTED}
 * (nobody has asked yet). Before Phase 8A all three looked identical: a missing row.
 */
public enum GarminRecoveryCoverageStatus {

    /** Values are stored for all five metric groups. */
    COMPLETE(false),

    /** Values are stored, but at least one metric group has none. Garmin may still fill it in later. */
    PARTIAL(true),

    /** Nothing is stored and a successful call reported no usable value for any metric. */
    CONFIRMED_NO_DATA(false),

    /** Nothing is stored and the last call failed. */
    COLLECTION_FAILED(true),

    /** Nothing is stored and no collection outcome is recorded - never attempted, or attempted before Phase 8A. */
    NOT_COLLECTED(true);

    private final boolean worthFetching;

    GarminRecoveryCoverageStatus(boolean worthFetching) {
        this.worthFetching = worthFetching;
    }

    /**
     * Whether fetching this day again could add something. False for {@link #COMPLETE} (nothing to
     * add) and for {@link #CONFIRMED_NO_DATA} (Garmin has nothing to give); true where data is
     * missing for a reason another attempt might resolve.
     */
    public boolean worthFetching() {
        return worthFetching;
    }
}
