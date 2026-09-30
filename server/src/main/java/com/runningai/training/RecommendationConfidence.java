package com.runningai.training;

/**
 * How clear the current rules and data are for this recommendation. It does not say how likely the
 * recommendation is to be "right", and it is separate from {@link DataSufficiency}.
 */
public enum RecommendationConfidence {
    LOW,
    MEDIUM,
    HIGH
}
