package com.runningai.integration.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.runningai.integration.intervals.WorkoutPublishApplicationService;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Registers RunningAI's MCP tools, only when {@code running-ai.mcp.enabled=true} (default false; the same switch also turns the
 * Spring AI MCP server itself on, see {@code application.yml}). Exactly one tool is registered on purpose:
 * {@link PublishWorkoutMcpTool#NAME}. Nothing else (no resources, prompts, completions, annotation-scanned or
 * {@code ToolCallback} beans) is exposed.
 */
@Configuration
@ConditionalOnProperty(prefix = "running-ai.mcp", name = "enabled", havingValue = "true")
public class McpToolConfig {

    @Bean
    PublishWorkoutMcpTool publishWorkoutMcpTool(WorkoutPublishApplicationService publishService, ObjectMapper objectMapper) {
        return new PublishWorkoutMcpTool(publishService, objectMapper);
    }

    /** A {@code List} bean on purpose: Spring AI's stateless server collects {@code List<SyncToolSpecification>} beans. */
    @Bean
    List<McpStatelessServerFeatures.SyncToolSpecification> runningAiMcpTools(PublishWorkoutMcpTool tool) {
        return List.of(tool.specification());
    }
}
