package com.runningai.coachrefresh

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue

/**
 * Coach data refresh pipeline (Phase 6H-9): keeps the RunningAI DB current just before a coach
 * generates a draft. Deliberately a SEPARATE top-level package from `com.runningai.coach`, not a
 * subpackage of it: `CoachArchitectureTest` forbids any file under `com.runningai.coach` from
 * importing `com.runningai.integration.garmin`/`intervals`, and this service's entire purpose is to
 * call both - so it cannot live there.
 */
@ConfigurationProperties(prefix = "running-ai.coach-refresh")
data class CoachDataRefreshProperties(
    /** Calendar days of recent-activity completion window, ending on the coach date D (D-6..D). */
    @param:DefaultValue("7") val recentActivityDays: Int,
    /** Intervals.icu fitness-model refresh window, ending on D (D-6..D after the plusDays(0) start offset of 6). */
    @param:DefaultValue("7") val fitnessRefreshDays: Int,
    /** Garmin recovery refresh window: D and D-1 only (never a 28-day backfill on every refresh). */
    @param:DefaultValue("2") val recoveryRefreshDays: Int,
    /** A stored fitness day older than this is not considered usable for a fresh coach decision. */
    @param:DefaultValue("2") val maxFitnessAgeDays: Int,
    /** Same idea for recovery. */
    @param:DefaultValue("2") val maxRecoveryAgeDays: Int,
    @param:DefaultValue val scheduler: Scheduler,
) {
    data class Scheduler(
        /** Automatic periodic refresh (CoachDataRefreshScheduler). OFF by default. */
        @param:DefaultValue("false") val enabled: Boolean,
        @param:DefaultValue("1h") val fixedDelay: String,
        @param:DefaultValue("1m") val initialDelay: String,
    )
}
