package com.runningai.training;

/**
 * A running pace range in seconds/km. Named {@code fast}/{@code slow} rather than
 * {@code min}/{@code max}: a smaller number of seconds/km is the faster pace, which
 * {@code min}/{@code max} would make ambiguous.
 */
public record PaceTarget(int fastSecondsPerKm, int slowSecondsPerKm) {
}
