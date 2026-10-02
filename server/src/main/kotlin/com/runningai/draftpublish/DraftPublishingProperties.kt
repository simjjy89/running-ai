package com.runningai.draftpublish

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue

/**
 * Switch for publishing APPROVED AI-coach drafts (Phase 6G, `RUNNING_AI_DRAFT_PUBLISHING_ENABLED`).
 *
 * Deliberately separate from `running-ai.workout-publishing.enabled` (`WORKOUT_PUBLISHING_ENABLED`),
 * which controls the legacy date-based deterministic path (`POST /api/v1/workout-publish`). Neither
 * switch implies the other. OFF by default: drafts can still be generated, revised, approved and
 * previewed, but nothing is sent to Intervals.icu until an operator turns this on.
 */
@ConfigurationProperties(prefix = "running-ai.draft-publishing")
data class DraftPublishingProperties(
    @param:DefaultValue("false") val enabled: Boolean,
)
