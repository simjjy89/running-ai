package com.runningai.training;

/**
 * A heart-rate range derived from a percentage band of the athlete's LTHR.
 * <p>
 * {@code minBpm}/{@code maxBpm} are nullable (Phase 6H-7.1): the deterministic
 * {@link RunningIntensityTargetPolicy} path always fills them (same LTHR used for both the percent
 * and the bpm figure), but an AI-coach draft carries only the approved {@code %LTHR} values, and
 * converting them to bpm here would mean recomputing against whatever LTHR happens to be on file at
 * publish time rather than what the athlete actually approved. The renderer only ever reads the
 * percent fields.
 */
public record HeartRateTarget(int minPercentLthr, int maxPercentLthr, Integer minBpm, Integer maxBpm) {
}
