package com.runningai.integration.mcp.train

import com.fasterxml.jackson.databind.ObjectMapper
import com.runningai.coach.WorkoutDraftApprovalService
import com.runningai.coach.WorkoutDraftService
import com.runningai.draftpublish.ApprovedWorkoutDraftPublishService
import io.modelcontextprotocol.server.McpStatelessServerFeatures
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Registers the six TRAINING-LIFECYCLE MCP tools (Phase 7F-1), only when
 * `running-ai.mcp.train-enabled=true` (default false).
 *
 * A third, independent switch on purpose. The three groups answer three different questions and
 * must be deployable separately:
 *  - `running-ai.mcp.enabled` (6C) - the legacy deterministic `publish_workout` write adapter;
 *  - `running-ai.mcp.read-enabled` (7C-2a) - the seven read-only tools;
 *  - `running-ai.mcp.train-enabled` (7F-1) - this draft -> approve -> publish lifecycle.
 *
 * With only `read-enabled` on - production's configuration - none of these tools is registered:
 * they are absent from `tools/list` and a forced `tools/call` for one fails as an unknown tool,
 * because no handler exists to reach. Turning this switch on still does not make publishing
 * possible on its own: `publish_approved_draft` reaches
 * [ApprovedWorkoutDraftPublishService], which refuses while `running-ai.draft-publishing.enabled`
 * is false, and that flag in turn cannot be true at the same time as legacy workout publishing
 * (`com.runningai.draftpublish.PublishingModeGuard`).
 *
 * The Spring AI MCP server itself turns on when ANY of the three is on - see
 * [com.runningai.integration.mcp.McpServerSwitchEnvironmentPostProcessor].
 */
@Configuration
@ConditionalOnProperty(prefix = "running-ai.mcp", name = ["train-enabled"], havingValue = "true")
class McpTrainToolConfig {

    @Bean
    fun trainMcpTools(
        draftService: WorkoutDraftService,
        approvalService: WorkoutDraftApprovalService,
        publishService: ApprovedWorkoutDraftPublishService,
        objectMapper: ObjectMapper,
    ): TrainMcpTools = TrainMcpTools(draftService, approvalService, publishService, objectMapper)

    /** A `List` bean on purpose, like the read and write adapters': the stateless server collects these. */
    @Bean
    fun runningAiMcpTrainTools(tools: TrainMcpTools): List<McpStatelessServerFeatures.SyncToolSpecification> =
        tools.specifications()
}
