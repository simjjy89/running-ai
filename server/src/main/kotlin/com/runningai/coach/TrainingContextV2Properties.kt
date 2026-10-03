package com.runningai.coach

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Compaction limits for [TrainingContextV2Builder] and [CoachContextSerializer] (Phase 6H-7). Plain
 * Kotlin defaults (not `@DefaultValue`) so the class is also directly constructible in code without
 * Spring's property binder — tests and `ClaudeCoachPromptBuilder`'s zero-arg construction rely on it.
 */
@ConfigurationProperties(prefix = "running-ai.coach.context-v2")
data class TrainingContextV2Properties(
    /** How many of the most recent in-window activities get full per-activity evidence. */
    val maxRecentActivities: Int = 8,
    /** How many interval groups of one activity are included, most recent structure first. */
    val maxIntervalGroupsPerActivity: Int = 3,
    /** Hard guard on the serialized snapshot (UTF-8 bytes); exceeding it is `TRAINING_CONTEXT_TOO_LARGE`. */
    val maxSnapshotBytes: Int = 65_536,
)
