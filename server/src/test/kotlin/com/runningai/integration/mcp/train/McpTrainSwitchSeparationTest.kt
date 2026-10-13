package com.runningai.integration.mcp.train

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.runningai.coach.WorkoutDraftApprovalService
import com.runningai.coach.WorkoutDraftService
import com.runningai.draftpublish.ApprovedWorkoutDraftPublishService
import com.runningai.integration.intervals.IntervalsWorkoutClient
import com.runningai.integration.intervals.IntervalsWorkoutPublisher
import com.runningai.integration.intervals.WorkoutPublishApplicationService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

/**
 * The 7F-1 switch is genuinely independent of the 7C-2a read switch: with ONLY `read-enabled` on -
 * which is production's configuration - the six training tools are not merely hidden, they are not
 * registered at all. A forced `tools/call` for one therefore fails as an unknown tool, because no
 * handler exists to reach, and the three lifecycle services are never touched.
 *
 * This is the server-side half of the defence; the gateway's per-tool scope policy is the other
 * half, and `tools/mcp-gateway/test/gateway-test.js` covers it.
 */
@SpringBootTest(
    properties = [
        "running-ai.mcp.read-enabled=true",
        "running-ai.garmin.connector.base-url=http://127.0.0.1:9",
        "running-ai.intervals.api-key=",
        "running-ai.intervals.base-url=http://127.0.0.1:9",
    ],
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class McpTrainSwitchSeparationTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper

    @MockitoBean private lateinit var draftService: WorkoutDraftService
    @MockitoBean private lateinit var approvalService: WorkoutDraftApprovalService
    @MockitoBean private lateinit var publishService: ApprovedWorkoutDraftPublishService
    @MockitoBean private lateinit var legacyPublish: WorkoutPublishApplicationService
    @MockitoBean private lateinit var publisher: IntervalsWorkoutPublisher
    @MockitoBean private lateinit var intervalsClient: IntervalsWorkoutClient

    private val trainingTools = listOf(
        "create_workout_draft", "get_workout_draft", "revise_workout_draft",
        "approve_workout_draft", "preview_draft_publish", "publish_approved_draft",
    )

    private fun rpc(method: String, params: String): JsonNode {
        val result = mockMvc.perform(
            post("/mcp").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .header("MCP-Protocol-Version", "2025-06-18")
                .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"$method\",\"params\":$params}"),
        ).andReturn()
        assertThat(result.response.status).`as`(result.response.contentAsString).isEqualTo(200)
        return objectMapper.readTree(result.response.contentAsString)
    }

    @Test
    fun `with only read-enabled on, the seven read tools are listed and no training tool is`() {
        val names = rpc("tools/list", "{}").path("result").path("tools").map { it.path("name").asText() }
        assertThat(names).containsExactlyInAnyOrder(
            "get_recent_activities", "get_activity_analysis", "get_training_context",
            "get_recovery_context", "get_fitness_trend", "get_workout_recommendation",
            "get_athlete_profile",
        )
        assertThat(names).doesNotContainAnyElementsOf(trainingTools)
        assertThat(names).doesNotContain("publish_workout")
    }

    @Test
    fun `forcing a training tool by name fails and reaches no lifecycle service`() {
        trainingTools.forEach { tool ->
            val response = rpc("tools/call", "{\"name\":\"$tool\",\"arguments\":{}}")
            val failed = response.has("error") || response.path("result").path("isError").asBoolean()
            assertThat(failed).`as`("$tool must not be callable while train-enabled is off").isTrue()
        }
        verifyNoInteractions(draftService, approvalService, publishService,
            legacyPublish, publisher, intervalsClient)
    }
}
