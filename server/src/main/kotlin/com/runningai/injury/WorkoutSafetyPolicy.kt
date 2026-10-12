package com.runningai.injury

import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * The one place that turns an [InjuryReport] (or its absence) into an [InjurySafetyDecision]
 * (Phase 7B-2). Pure and stateless: every entry point (HTTP draft generation, the AI coach, the
 * legacy deterministic publish pipeline, the scheduler, MCP) calls the *same* instance of this
 * class through [com.runningai.injury.WorkoutSafetyGateService], so there is exactly one safety
 * policy in the system, not one per entry point.
 *
 * Rules (STEP C of the Phase 7B-2 work order, applied in this precedence):
 *  1. No report at all -> REST only. The absence of data is never treated as "no injury".
 *  2. Latest status UNKNOWN -> REST only.
 *  3. Latest report older than the configured freshness window -> REST only, for ACTIVE, RESOLVED
 *     and UNKNOWN alike: an old all-clear does not keep certifying today's training forever.
 *  4. Latest status ACTIVE (regardless of which specific symptoms are set) -> REST only. Omitting
 *     `painOnWalking` on a later call never clears this: only a new report whose `status` is
 *     explicitly RESOLVED does.
 *  5. Latest status RESOLVED and fresh -> normal training may be considered. This is a server
 *     permission to proceed, not a medical clearance; the coach's own judgement still applies on
 *     top of it.
 */
@Component
class WorkoutSafetyPolicy(private val properties: InjurySafetyProperties) {

    fun evaluate(latest: InjuryReport?, now: Instant): InjurySafetyDecision {
        if (latest == null) {
            return decision(
                allowed = false,
                reason = InjurySafetyReasonCode.NO_INJURY_STATE_RECORDED,
                message = "No injury/pain status has ever been recorded for this athlete. Report one " +
                    "via POST /api/v1/athlete/injury-reports (RESOLVED, with no pain, if that is " +
                    "actually the case) before a running, interval or long workout can be generated.",
                latest = null,
                now = now,
            )
        }

        val recordedAt = latest.recordedAt ?: latest.reportedAt
        val age = Duration.between(recordedAt, now)
        if (age > properties.stalenessThreshold()) {
            return decision(
                allowed = false,
                reason = InjurySafetyReasonCode.INJURY_STATE_STALE,
                message = "The last injury/pain status (status=${latest.status}, recorded $recordedAt) is " +
                    "older than the ${properties.stalenessHours}h freshness window. Reconfirm current " +
                    "status via POST /api/v1/athlete/injury-reports before a running, interval or long " +
                    "workout can be generated.",
                latest = latest,
                now = now,
            )
        }

        return when (latest.status) {
            InjuryStatus.UNKNOWN -> decision(
                allowed = false,
                reason = InjurySafetyReasonCode.INJURY_STATE_UNKNOWN,
                message = "The latest injury/pain status is UNKNOWN. Report ACTIVE or RESOLVED via " +
                    "POST /api/v1/athlete/injury-reports before a running, interval or long workout " +
                    "can be generated.",
                latest = latest,
                now = now,
            )
            InjuryStatus.ACTIVE -> decision(
                allowed = false,
                reason = InjurySafetyReasonCode.INJURY_ACTIVE,
                message = "An ACTIVE injury/pain report is on file" +
                    (latest.bodySite?.let { " ($it${latest.side?.let { s -> ", $s" } ?: ""})" } ?: "") +
                    ". Running, interval and long training are blocked until the athlete explicitly " +
                    "reports status=RESOLVED.",
                latest = latest,
                now = now,
            )
            InjuryStatus.RESOLVED -> decision(
                allowed = true,
                reason = InjurySafetyReasonCode.INJURY_RESOLVED_CONFIRMED,
                message = "Injury/pain status was explicitly reported RESOLVED on $recordedAt. Normal " +
                    "training may be considered; this is a data permission, not a medical clearance.",
                latest = latest,
                now = now,
            )
        }
    }

    private fun decision(
        allowed: Boolean,
        reason: InjurySafetyReasonCode,
        message: String,
        latest: InjuryReport?,
        now: Instant,
    ) = InjurySafetyDecision(allowed, reason, message, latest, now)
}
