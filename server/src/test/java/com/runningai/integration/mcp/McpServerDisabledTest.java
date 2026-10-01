package com.runningai.integration.mcp;

import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * With the MCP switch off (pinned, so a developer's environment cannot change it) neither the Spring AI MCP server, its
 * transport nor RunningAI's tool exists, and {@code /mcp} is not served.
 */
@SpringBootTest(properties = {"running-ai.mcp.enabled=false", "running-ai.intervals.api-key=",
        "running-ai.intervals.base-url=http://127.0.0.1:9"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class McpServerDisabledTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void noMcpServerNoToolNoTransport() {
        assertThat(context.getBeansOfType(McpStatelessSyncServer.class)).isEmpty();
        assertThat(context.getBeansOfType(McpSyncServer.class)).isEmpty();
        assertThat(context.getBeansOfType(PublishWorkoutMcpTool.class)).isEmpty();
        assertThat(context.getBeansOfType(McpToolConfig.class)).isEmpty();
        assertThat(context.getBeanNamesForType(io.modelcontextprotocol.spec.McpStatelessServerTransport.class)).isEmpty();
    }

    @Test
    void mcpEndpointIsNotServed() throws Exception {
        int status = mockMvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(404);
    }
}
