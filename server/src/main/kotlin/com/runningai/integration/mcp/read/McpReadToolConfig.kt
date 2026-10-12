package com.runningai.integration.mcp.read

import com.fasterxml.jackson.databind.ObjectMapper
import com.runningai.activity.ActivityRepository
import com.runningai.analysis.RunningActivityAnalysisService
import com.runningai.athlete.AthleteService
import com.runningai.coach.CoachContextSerializer
import com.runningai.coach.RecoveryContextBuilder
import com.runningai.coach.TrainingContextV2Builder
import com.runningai.enrichment.IntervalsEnrichmentStore
import com.runningai.injury.WorkoutSafetyGateService
import com.runningai.training.WorkoutRecommendationService
import io.modelcontextprotocol.server.McpStatelessServerFeatures
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * Registers the READ-ONLY RunningAI MCP tools, only when `running-ai.mcp.read-enabled=true`
 * (default false) - deliberately a SEPARATE switch from `running-ai.mcp.enabled`, which gates the
 * write adapter ([com.runningai.integration.mcp.McpToolConfig] / `publish_workout`). With only
 * read-enabled on, `publish_workout` is not registered at all: it is absent from `tools/list` and a
 * forced `tools/call` for it fails as an unknown tool, because there is no handler to reach.
 *
 * The Spring AI MCP server itself turns on when EITHER switch is on - see
 * [com.runningai.integration.mcp.McpServerSwitchEnvironmentPostProcessor].
 */
@Configuration
@ConditionalOnProperty(prefix = "running-ai.mcp", name = ["read-enabled"], havingValue = "true")
class McpReadToolConfig {

    @Bean
    fun readMcpTools(
        athleteService: AthleteService,
        activities: ActivityRepository,
        analysisService: RunningActivityAnalysisService,
        contextBuilder: TrainingContextV2Builder,
        contextSerializer: CoachContextSerializer,
        recoveryContextBuilder: RecoveryContextBuilder,
        intervalsStore: IntervalsEnrichmentStore,
        recommendationService: WorkoutRecommendationService,
        safetyGate: WorkoutSafetyGateService,
        objectMapper: ObjectMapper,
        clock: Clock,
    ): ReadMcpTools = ReadMcpTools(
        athleteService, activities, analysisService, contextBuilder, contextSerializer,
        recoveryContextBuilder, intervalsStore, recommendationService, safetyGate, objectMapper, clock,
    )

    /** A `List` bean on purpose, like the write adapter's: the stateless server collects these. */
    @Bean
    fun runningAiMcpReadTools(tools: ReadMcpTools): List<McpStatelessServerFeatures.SyncToolSpecification> =
        tools.specifications()
}
