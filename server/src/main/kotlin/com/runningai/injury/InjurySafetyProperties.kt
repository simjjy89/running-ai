package com.runningai.injury

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import java.time.Duration

/**
 * Freshness policy for athlete-reported injury/pain status (Phase 7B-2).
 *
 * [stalenessHours] is the longest an injury/pain status (ACTIVE, RESOLVED or UNKNOWN alike) may go
 * without a fresh report before [WorkoutSafetyPolicy] stops trusting it and falls back to REST-only
 * (reason [InjurySafetyReasonCode.INJURY_STATE_STALE]). 48 hours by default: long enough that a
 * same-day or next-day confirmation does not need repeating on every single coach call, short
 * enough that a multi-day-old reading about a specific reported symptom (as opposed to the athlete
 * simply never having reported anything, which is already blocked on its own) is never silently
 * treated as still describing today. This applies equally to a stale RESOLVED report: an old
 * all-clear does not keep certifying today's training forever, because the underlying condition can
 * change without the athlete thinking to say so.
 */
@ConfigurationProperties(prefix = "running-ai.injury-safety")
data class InjurySafetyProperties(
    @param:DefaultValue("48") val stalenessHours: Long = 48,
) {
    fun stalenessThreshold(): Duration = Duration.ofHours(stalenessHours)
}
