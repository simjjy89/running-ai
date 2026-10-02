package com.runningai.draftpublish

import com.runningai.integration.intervals.WorkoutPublishProperties
import org.springframework.stereotype.Component

/**
 * Configuration invariant (Phase 6G.1): **legacy publishing XOR AI draft publishing**.
 *
 * The legacy deterministic path (`WORKOUT_PUBLISHING_ENABLED`, `POST /api/v1/workout-publish`) and
 * the approved-draft path (`RUNNING_AI_DRAFT_PUBLISHING_ENABLED`, `POST /api/v1/workout-drafts/{id}/publish`)
 * write the same per-athlete, per-date Intervals.icu event, so with both on one could silently
 * overwrite the workout the other published. Both off, or exactly one on, is valid.
 *
 * Checked when this singleton is created, i.e. while the application context starts: an invalid
 * combination stops startup with an explicit message instead of failing at the first publish.
 * It only reads the two switches; it changes neither path's behaviour, the scheduler or any default.
 */
@Component
class PublishingModeGuard(legacy: WorkoutPublishProperties, draft: DraftPublishingProperties) {

    init {
        check(legacyEnabled = legacy.enabled(), draftEnabled = draft.enabled)
    }

    companion object {
        const val MESSAGE =
            "Legacy workout publishing and AI draft publishing cannot be enabled at the same time " +
                "(WORKOUT_PUBLISHING_ENABLED and RUNNING_AI_DRAFT_PUBLISHING_ENABLED are both true); enable at most one"

        fun check(legacyEnabled: Boolean, draftEnabled: Boolean) {
            if (legacyEnabled && draftEnabled) {
                throw IllegalStateException(MESSAGE)
            }
        }
    }
}
