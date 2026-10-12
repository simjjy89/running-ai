package com.runningai.integration.mcp.read

import com.fasterxml.jackson.databind.ObjectMapper
import com.runningai.integration.intervals.IntervalsWorkoutClient
import com.runningai.integration.intervals.IntervalsWorkoutPublisher
import com.runningai.integration.intervals.WorkoutPublishApplicationService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

/**
 * The two switches are genuinely independent (Phase 7C-2a): with BOTH on, the write adapter and
 * the seven read tools coexist on one server - proving read mode did not re-wire or replace the
 * 6C adapter, only sit beside it. (Read-only exposure is covered by [McpReadServerApiTest]; the
 * write-only case is the existing `McpServerProtocolTest`.)
 */
@SpringBootTest(
    properties = [
        "running-ai.mcp.enabled=true",
        "running-ai.mcp.read-enabled=true",
        "running-ai.garmin.connector.base-url=http://127.0.0.1:9",
        "running-ai.intervals.api-key=",
        "running-ai.intervals.base-url=http://127.0.0.1:9",
    ],
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class McpReadWriteSeparationTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper

    @MockitoBean private lateinit var publishService: WorkoutPublishApplicationService
    @MockitoBean private lateinit var publisher: IntervalsWorkoutPublisher
    @MockitoBean private lateinit var intervalsClient: IntervalsWorkoutClient

    @Test
    fun `with both switches on, the write tool and the seven read tools are all listed`() {
        val body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}"
        val result = mockMvc.perform(
            post("/mcp").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .header("MCP-Protocol-Version", "2025-06-18")
                .content(body),
        ).andReturn()
        assertThat(result.response.status).isEqualTo(200)
        val tools = objectMapper.readTree(result.response.contentAsString).path("result").path("tools")

        val names = tools.map { it.path("name").asText() }
        assertThat(names).containsExactlyInAnyOrder(
            "publish_workout",
            "get_recent_activities", "get_activity_analysis", "get_training_context",
            "get_recovery_context", "get_fitness_trend", "get_workout_recommendation",
            "get_athlete_profile",
        )
        // and the hints stay truthful per tool
        val byName = tools.associateBy { it.path("name").asText() }
        assertThat(byName["publish_workout"]!!.path("annotations").path("readOnlyHint").asBoolean()).isFalse()
        assertThat(byName["get_recent_activities"]!!.path("annotations").path("readOnlyHint").asBoolean()).isTrue()
    }
}
