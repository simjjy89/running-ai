package com.runningai.training;

/**
 * Descriptive label of one athlete-local day. Ordered by same-day priority (highest first):
 * LONG > QUALITY_CANDIDATE > EASY_OR_GENERAL > INDOOR_CYCLING > REST.
 * <p>
 * LONG and QUALITY_CANDIDATE are RunningAI heuristics, not physiological facts.
 * QUALITY_CANDIDATE is reserved: the normalised activity carries no field that identifies quality
 * work with confidence (duration, distance, average/max HR only; no laps, pace zones or personal
 * threshold), so it is never assigned in this phase.
 */
public enum SessionClassification {
    LONG,
    QUALITY_CANDIDATE,
    EASY_OR_GENERAL,
    INDOOR_CYCLING,
    REST
}
