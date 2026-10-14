package com.runningai.coach

import java.time.LocalDate

/**
 * The provider- and version-neutral shape an [AiCoach] and [WorkoutDraftValidator] actually depend
 * on (Phase 6H-7). [TrainingContext] (V1) and [TrainingContextV2] both implement it; callers that
 * only need the date, the athlete's thresholds or the session constraints never need to know which
 * one they were handed.
 */
interface CoachTrainingContext {
    val date: LocalDate
    val athlete: AthleteThresholds
    val constraints: SessionConstraints

    /**
     * What the athlete has reported about pain and injury (Phase 7F-3A). Present on both context
     * versions, because the gap it closes - a RESOLVED report silencing the safety constraint and
     * leaving the coach unaware that there was an injury at all - exists in both.
     *
     * Nullable, and null on a hand-built context only: both production builders always populate it
     * (`CoachInjuryHistoryContextTest` fails the build otherwise). A null is never a safety hole -
     * `WorkoutSafetyGateService` enforces the gate on its own, before and after every coach call,
     * and does not consult this field - but the coach is told to read it as "unknown" and design
     * conservatively rather than as "nothing was ever reported".
     */
    val injuryHistory: InjuryHistoryContext?
}

/**
 * Which [CoachTrainingContext] shape a prompt or a persisted draft was built from. V1 is the
 * compatibility/reference implementation (`TrainingDecisionContext` + threshold + recovery); V2 adds
 * Garmin detail, RunningAI Analysis and Intervals.icu training-model evidence. Selected by
 * `running-ai.coach.context-version` (default V1); persisted per-draft regardless of which was used.
 */
enum class ContextVersion { V1, V2 }
