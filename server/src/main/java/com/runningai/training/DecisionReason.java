package com.runningai.training;

/** Facts in the decision context that shaped the candidate list. Declaration order is the fixed output order. */
public enum DecisionReason {
    /** A long run on the as-of day or the day before. */
    LONG_RUN_RECENT,
    /** Three or more consecutive active days ending on the as-of day. */
    MULTIPLE_ACTIVE_DAYS,
    /** The as-of day itself has no load. */
    REST_DAY_RECENT,
    LOAD_INCREASING,
    LOAD_DECREASING,
    /** Activity history exists, but none in the last 7 days. */
    LOW_RECENT_ACTIVITY,
    /** No run in the history window. */
    NO_RECENT_RUNNING,
    /** Indoor cycling on the as-of day or the day before. */
    RECENT_CYCLING,
    /** No supported activity in the whole history window. */
    LIMITED_HISTORY
}
