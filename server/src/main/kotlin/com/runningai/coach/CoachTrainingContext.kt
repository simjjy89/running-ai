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
}

/**
 * Which [CoachTrainingContext] shape a prompt or a persisted draft was built from. V1 is the
 * compatibility/reference implementation (`TrainingDecisionContext` + threshold + recovery); V2 adds
 * Garmin detail, RunningAI Analysis and Intervals.icu training-model evidence. Selected by
 * `running-ai.coach.context-version` (default V1); persisted per-draft regardless of which was used.
 */
enum class ContextVersion { V1, V2 }
