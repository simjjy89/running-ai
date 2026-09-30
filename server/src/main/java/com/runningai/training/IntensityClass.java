package com.runningai.training;

/**
 * Qualitative intensity label of a workout intent. There is no pace, heart-rate zone, LTHR or RPE
 * model yet, so this is a label only, not a measurable target.
 */
public enum IntensityClass {
    NONE,
    VERY_EASY,
    EASY,
    MODERATE,
    HARD
}
