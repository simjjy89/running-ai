package com.runningai.integration.mcp;

import com.runningai.integration.intervals.IntervalsWorkoutPublisher;
import com.runningai.training.WorkoutIntensityTargetService;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * MCP on, publishing master switch off (pinned), real application service: the tool cannot bypass the master switch and
 * nothing behind the service is ever reached. The publisher is a mock anyway, so no calendar could be written.
 */
@SpringBootTest(properties = {"running-ai.mcp.enabled=true", "running-ai.workout-publishing.enabled=false",
        "running-ai.intervals.api-key=", "running-ai.intervals.base-url=http://127.0.0.1:9"})
@ActiveProfiles("test")
class McpMasterSwitchTest {

    @Autowired
    private PublishWorkoutMcpTool tool;

    @MockitoBean
    private IntervalsWorkoutPublisher publisher;

    @MockitoBean
    private WorkoutIntensityTargetService targetService;

    @Test
    @SuppressWarnings("unchecked")
    void masterSwitchOffRefusesTheToolBeforeAnyWork() {
        McpSchema.CallToolResult result = tool.call(Map.of("date", "2026-10-02"));

        assertThat(result.isError()).isTrue();
        assertThat((Map<String, Object>) result.structuredContent()).containsEntry("code", "WORKOUT_PUBLISHING_DISABLED");
        verifyNoInteractions(targetService, publisher);
    }
}
