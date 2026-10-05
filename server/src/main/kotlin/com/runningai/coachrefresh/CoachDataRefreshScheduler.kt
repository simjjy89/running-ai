package com.runningai.coachrefresh

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** Turns on Spring scheduling only when the coach-data-refresh scheduler is explicitly enabled. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "running-ai.coach-refresh.scheduler", name = ["enabled"], havingValue = "true")
class CoachDataRefreshSchedulingConfig

/**
 * Runs the same pipeline as `POST /api/v1/coach/data-refresh`, periodically. Only present when
 * `running-ai.coach-refresh.scheduler.enabled=true` (default off; stays off through this phase's own
 * live validation per the work order). Goes through [CoachDataRefreshService] only, so it shares the
 * single-flight guard with the manual API and duplicates no refresh logic. Fixed-delay: the next tick
 * is measured from the end of the previous run. Any failure ends the current tick - no retry inside a
 * tick - so a rate limit or auth problem is never hammered; the next regular tick simply tries again.
 */
@Component
@ConditionalOnProperty(prefix = "running-ai.coach-refresh.scheduler", name = ["enabled"], havingValue = "true")
class CoachDataRefreshScheduler(private val service: CoachDataRefreshService) {

    private val log = LoggerFactory.getLogger(CoachDataRefreshScheduler::class.java)

    @Scheduled(
        fixedDelayString = "\${running-ai.coach-refresh.scheduler.fixed-delay:1h}",
        initialDelayString = "\${running-ai.coach-refresh.scheduler.initial-delay:1m}",
    )
    fun runScheduledRefresh() {
        try {
            val r = service.refresh(null)
            log.info(
                "Coach data refresh scheduled tick completed: date={} readyForCoach={} reasons={}",
                r.date, r.readyForCoach, r.reasons,
            )
        } catch (e: CoachDataRefreshAlreadyRunningException) {
            log.info("Coach data refresh scheduled tick SKIPPED_ALREADY_RUNNING; waiting for the next tick")
        } catch (e: RuntimeException) {
            log.error("Coach data refresh scheduled tick failed unexpectedly: {}; no retry until the next tick", e.javaClass.simpleName)
        }
    }
}
