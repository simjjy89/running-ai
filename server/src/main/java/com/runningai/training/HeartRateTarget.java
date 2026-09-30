package com.runningai.training;

/** A heart-rate range derived from a percentage band of the athlete's LTHR. */
public record HeartRateTarget(int minPercentLthr, int maxPercentLthr, int minBpm, int maxBpm) {
}
