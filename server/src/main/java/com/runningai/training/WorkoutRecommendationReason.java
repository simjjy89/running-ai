package com.runningai.training;

/**
 * Facts of the decision context that shaped the recommended intent. Scheduling vocabulary only, not
 * medical or readiness statements. Declaration order is the fixed output order.
 */
public enum WorkoutRecommendationReason {
    /** A long run on the as-of day or the day before. */
    RECENT_LONG_RUN,
    /** Three or more consecutive active days ending on the as-of day. */
    MULTIPLE_ACTIVE_DAYS,
    /** The as-of day has no load. */
    RECENT_REST,
    LOAD_TREND_INCREASING,
    LOAD_TREND_STABLE,
    LOAD_TREND_DECREASING,
    LOW_RECENT_ACTIVITY,
    NO_RECENT_RUNNING,
    RECENT_CYCLING,
    /** A long run is due by the calendar schedule. */
    LONG_RUN_DUE,
    LIMITED_HISTORY,
    /** QUALITY is a candidate but is not selected: no quality-session model exists yet. */
    QUALITY_HISTORY_UNAVAILABLE,
    /** No restricting or scheduling condition applied, so the default easy session was chosen. */
    DEFAULT_EASY
}
