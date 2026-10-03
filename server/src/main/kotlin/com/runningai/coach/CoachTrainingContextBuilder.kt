package com.runningai.coach

import org.springframework.stereotype.Service
import java.time.LocalDate

/**
 * Selects [TrainingContextBuilder] (V1) or [TrainingContextV2Builder] (V2) deterministically from
 * `running-ai.coach.context-version` (Phase 6H-7). [WorkoutDraftService] depends on this router only,
 * never on a concrete builder, so it never knows which context shape it is handing the coach.
 */
@Service
class CoachTrainingContextBuilder(
    private val v1: TrainingContextBuilder,
    private val v2: TrainingContextV2Builder,
    private val properties: CoachProperties,
) {

    fun today(): LocalDate = v1.today()

    fun build(date: LocalDate, constraints: SessionConstraints = SessionConstraints()): CoachTrainingContext =
        build(date, constraints, properties.contextVersion)

    /** Explicit version override, for the read-only preview endpoint only. */
    fun build(date: LocalDate, constraints: SessionConstraints, version: ContextVersion): CoachTrainingContext =
        when (version) {
            ContextVersion.V1 -> v1.build(date, constraints)
            ContextVersion.V2 -> v2.build(date, constraints)
        }
}
