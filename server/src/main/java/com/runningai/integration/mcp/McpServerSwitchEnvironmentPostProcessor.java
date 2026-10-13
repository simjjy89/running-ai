package com.runningai.integration.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * Turns the Spring AI MCP server on exactly when at least one RunningAI tool group is enabled
 * (Phase 7C-2a; third group added in 7F-1): the write adapter ({@code running-ai.mcp.enabled},
 * Phase 6C), the read-only tools ({@code running-ai.mcp.read-enabled}), or the training-lifecycle
 * tools ({@code running-ai.mcp.train-enabled}). YAML cannot express an OR of several properties,
 * and binding the server only to the write switch (as before 7C-2a) would force a read-only
 * deployment to also register {@code publish_workout}, defeating the read/write separation.
 *
 * Registered in {@code META-INF/spring.factories}. Behaviour is unchanged for every existing
 * configuration: with {@code read-enabled} and {@code train-enabled} at their default (false), the
 * computed value equals {@code running-ai.mcp.enabled}, exactly what application.yml bound before.
 */
public class McpServerSwitchEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        boolean write = environment.getProperty("running-ai.mcp.enabled", Boolean.class, false);
        boolean read = environment.getProperty("running-ai.mcp.read-enabled", Boolean.class, false);
        boolean train = environment.getProperty("running-ai.mcp.train-enabled", Boolean.class, false);
        environment.getPropertySources().addFirst(new MapPropertySource(
                "runningAiMcpServerSwitch", Map.of("spring.ai.mcp.server.enabled", write || read || train)));
    }
}
