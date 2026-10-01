package com.runningai.integration.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runningai.integration.intervals.IntervalsPublishOperation;
import com.runningai.integration.intervals.WorkoutPublishAlreadyRunningException;
import com.runningai.integration.intervals.WorkoutPublishApplicationService;
import com.runningai.integration.intervals.WorkoutPublishResponse;
import com.runningai.training.CandidateTrainingType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Protocol level: JSON-RPC over the local Streamable HTTP (stateless) {@code /mcp} endpoint, MCP switched on, the application
 * service mocked (no Intervals, no real calendar).
 */
@SpringBootTest(properties = {"running-ai.mcp.enabled=true", "running-ai.intervals.api-key=",
        "running-ai.intervals.base-url=http://127.0.0.1:9"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class McpServerProtocolTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 2);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private WorkoutPublishApplicationService publishService;

    private JsonNode rpc(String method, String params) throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\"" + (params == null ? "" : ",\"params\":" + params) + "}";
        MvcResult result = mockMvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                        .header("MCP-Protocol-Version", "2025-06-18")
                        .content(body))
                .andReturn();
        String response = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).as(response).isEqualTo(200);
        JsonNode json = objectMapper.readTree(response);
        assertThat(json.has("error")).as(response).isFalse();
        return json;
    }

    private JsonNode callPublish(String arguments) throws Exception {
        return rpc("tools/call", "{\"name\":\"publish_workout\",\"arguments\":" + arguments + "}").path("result");
    }

    @Test
    void initializeAdvertisesToolsOnly() throws Exception {
        JsonNode result = rpc("initialize", "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"test-client\",\"version\":\"1\"}}").path("result");

        assertThat(result.path("serverInfo").path("name").asText()).isEqualTo("running-ai");
        assertThat(result.path("capabilities").has("tools")).isTrue();
        assertThat(result.path("capabilities").has("resources")).isFalse();
        assertThat(result.path("capabilities").has("prompts")).isFalse();
        assertThat(result.path("capabilities").has("completions")).isFalse();
    }

    @Test
    void exactlyOneToolIsListed() throws Exception {
        JsonNode tools = rpc("tools/list", "{}").path("result").path("tools");

        assertThat(tools.size()).isEqualTo(1);
        assertThat(tools.get(0).path("name").asText()).isEqualTo("publish_workout");
        assertThat(tools.get(0).path("inputSchema").path("required").get(0).asText()).isEqualTo("date");
        verifyNoInteractions(publishService);
    }

    @Test
    void toolCallPublishesTheDateOnceAndReturnsTheResult() throws Exception {
        when(publishService.publish(DATE)).thenReturn(
                new WorkoutPublishResponse(DATE, IntervalsPublishOperation.CREATED, true, CandidateTrainingType.EASY, 3));

        JsonNode result = callPublish("{\"date\":\"2026-10-02\"}");

        verify(publishService, times(1)).publish(DATE);
        assertThat(result.path("isError").asBoolean()).isFalse();
        JsonNode content = result.path("structuredContent");
        assertThat(content.path("success").asBoolean()).isTrue();
        assertThat(content.path("operation").asText()).isEqualTo("CREATED");
        assertThat(content.path("verified").asBoolean()).isTrue();
        assertThat(content.path("stepCount").asInt()).isEqualTo(3);
        assertThat(result.path("content").get(0).path("text").asText()).contains("\"operation\":\"CREATED\"");
    }

    @Test
    void noChangeIsASuccessToo() throws Exception {
        when(publishService.publish(DATE)).thenReturn(
                new WorkoutPublishResponse(DATE, IntervalsPublishOperation.NO_CHANGE, true, CandidateTrainingType.EASY, 3));

        JsonNode result = callPublish("{\"date\":\"2026-10-02\"}");

        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(result.path("structuredContent").path("operation").asText()).isEqualTo("NO_CHANGE");
    }

    @Test
    void invalidDateNeverReachesTheService() throws Exception {
        JsonNode result = callPublish("{\"date\":\"tomorrow\"}");

        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.path("structuredContent").path("code").asText()).isEqualTo("INVALID_DATE");
        verifyNoInteractions(publishService);
    }

    @Test
    void missingDateNeverReachesTheService() throws Exception {
        JsonNode result = callPublish("{}");

        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.path("structuredContent").path("code").asText()).isEqualTo("INVALID_ARGUMENT");
        verifyNoInteractions(publishService);
    }

    @Test
    void alreadyRunningCodeSurvivesTheProtocol() throws Exception {
        when(publishService.publish(any())).thenThrow(new WorkoutPublishAlreadyRunningException(DATE));

        JsonNode result = callPublish("{\"date\":\"2026-10-02\"}");

        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.path("structuredContent").path("code").asText()).isEqualTo("WORKOUT_PUBLISH_ALREADY_RUNNING");
    }

    @Test
    void existingRestApiIsUnaffected() throws Exception {
        int status = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/health"))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(200);
    }
}
