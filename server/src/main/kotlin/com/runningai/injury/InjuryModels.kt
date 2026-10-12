package com.runningai.injury

import java.time.Instant

/** Which side of the body a reported symptom is on, when that is meaningful. */
enum class InjurySide {
    LEFT,
    RIGHT,
    BOTH,
    UNSPECIFIED,
}

/**
 * The athlete's own characterisation of a reported symptom's current status. This is always a
 * self-report, never a medical diagnosis.
 *
 * - [ACTIVE]: the athlete says the symptom is ongoing.
 * - [RESOLVED]: the athlete has explicitly said the symptom is gone. Only ever reached by an
 *   explicit report naming this status - nothing in this system infers it from time passing or
 *   from a missing follow-up.
 * - [UNKNOWN]: status was not confirmed (used for a report that only records "I don't know" rather
 *   than leaving no row at all).
 */
enum class InjuryStatus {
    ACTIVE,
    RESOLVED,
    UNKNOWN,
}

/**
 * One athlete-reported symptom status, as a row in the append-only `injury_report` ledger
 * (Phase 7B-2). [reportedAt] is when the athlete says the described status applies from;
 * [recordedAt] is when RunningAI actually stored this report (server time, immutable, never
 * backdated) - the two can differ when a report is entered after the fact.
 *
 * Every field except [status], [reportedAt] and [recordedAt] is optional: an athlete may report as
 * much or as little as they know, and a missing field is simply unreported, never assumed to be
 * "no symptom".
 */
data class InjuryReport(
    val id: Long?,
    val athleteId: Long,
    val bodySite: String?,
    val side: InjurySide?,
    val painPresent: Boolean?,
    val painIntensity: Int?,
    val painOnWalking: Boolean?,
    val painOnStairs: Boolean?,
    val swelling: Boolean?,
    val rangeOfMotionLimited: Boolean?,
    val status: InjuryStatus,
    val notes: String?,
    val reportedAt: Instant,
    val recordedAt: Instant?,
)

/** Why [WorkoutSafetyPolicy] reached the decision it did. Never a medical diagnosis code. */
enum class InjurySafetyReasonCode {
    /** No injury/pain status has ever been recorded for this athlete. */
    NO_INJURY_STATE_RECORDED,

    /** The latest report's status is explicitly UNKNOWN. */
    INJURY_STATE_UNKNOWN,

    /** The latest report exists but is older than the configured freshness window. */
    INJURY_STATE_STALE,

    /** The latest report's status is ACTIVE. */
    INJURY_ACTIVE,

    /** The latest report's status is RESOLVED and it is within the freshness window. */
    INJURY_RESOLVED_CONFIRMED,
}

/**
 * The server-enforced safety verdict for today's training, independent of what any AI coach call
 * decides (Phase 7B-2). [nonRestTrainingAllowed] = false means only a REST workout may be
 * generated, approved or published; it never means a non-REST workout is impossible to request,
 * only that the server refuses to store or publish one.
 *
 * This is deliberately binary (REST-only vs normal) rather than a finer per-intensity scale: the
 * underlying data (an athlete's free-form symptom report) is not reliable enough to safely
 * distinguish "safe for cross-training but not running" from "safe for an easy run" in code, so
 * that judgement is left to the coach's prose - never to a system that would then have to defend a
 * specific intensity ceiling it cannot justify. REST is the one workout type that is never unsafe.
 */
data class InjurySafetyDecision(
    val nonRestTrainingAllowed: Boolean,
    val reasonCode: InjurySafetyReasonCode,
    val message: String,
    val latestReport: InjuryReport?,
    val evaluatedAt: Instant,
)
