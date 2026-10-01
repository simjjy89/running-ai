package com.runningai.coach

import com.fasterxml.jackson.databind.ObjectMapper
import com.runningai.coach.CoachTestFixtures.draft
import com.runningai.integration.intervals.IntervalsWorkoutClient
import com.runningai.integration.intervals.IntervalsWorkoutPublisher
import com.runningai.integration.intervals.WorkoutPublishApplicationService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * The draft API end-to-end over the real context builder, validator, schema and REST stack, with
 * only the AI itself faked — so no automated run ever calls the Claude CLI.
 *
 * **Crucially, the publishing beans are mocked and asserted untouched**: generating or revising a
 * draft must never reach them, and this test is run with the master publish switch explicitly ON
 * to prove the separation is structural, not a side effect of the switch being off.
 */
@SpringBootTest(
    properties = [
        "running-ai.workout-publishing.enabled=true",
        "running-ai.intervals.api-key=",
        "running-ai.intervals.base-url=http://127.0.0.1:9",
    ],
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkoutDraftApiTest {

    @TestConfiguration
    class FakeCoachConfig {
        @Bean
        @Primary
        fun fakeAiCoach(): FakeAiCoach = FakeAiCoach()
    }

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var repository: WorkoutDraftRepository
    @Autowired private lateinit var coach: FakeAiCoach

    @MockitoBean private lateinit var publishService: WorkoutPublishApplicationService
    @MockitoBean private lateinit var publisher: IntervalsWorkoutPublisher
    @MockitoBean private lateinit var intervalsClient: IntervalsWorkoutClient

    @BeforeEach
    fun reset() {
        coach.reset()
    }

    @AfterEach
    fun cleanUp() {
        repository.deleteAll()
        verifyNoInteractions(publishService, publisher, intervalsClient)
    }

    private fun generate(body: String) =
        mockMvc.perform(post("/api/v1/workout-drafts").contentType(MediaType.APPLICATION_JSON).content(body))

    @Test
    fun `generate creates version 1 and stores it`() {
        generate("""{"date":"2026-10-02","availableMinutes":45,"environment":"TREADMILL"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").isNumber)
            .andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andExpect(jsonPath("$.date").value("2026-10-02"))
            .andExpect(jsonPath("$.provider").value("CLAUDE"))
            .andExpect(jsonPath("$.segments").isArray)
            .andExpect(jsonPath("$.assessment.rationale").isNotEmpty)

        assertThat(repository.count()).isEqualTo(1)
    }

    @Test
    fun `the request constraints actually reach the coach`() {
        generate("""{"date":"2026-10-02","availableMinutes":30,"environment":"TREADMILL",
            "userFeedback":"legs heavy","requestedGoal":"10k PB","painOrFatigueFeedback":"sore calf"}""")
            .andExpect(status().isOk)

        val constraints = coach.createdContexts.single().constraints
        assertThat(constraints.availableMinutes).isEqualTo(30)
        assertThat(constraints.environment).isEqualTo(TrainingEnvironment.TREADMILL)
        assertThat(constraints.userFeedback).isEqualTo("legs heavy")
        assertThat(constraints.requestedGoal).isEqualTo("10k PB")
        assertThat(constraints.painOrFatigueFeedback).isEqualTo("sore calf")
    }

    @Test
    fun `an omitted date defaults to the athlete-local today`() {
        generate("""{"availableMinutes":40}""").andExpect(status().isOk)

        assertThat(coach.createdContexts.single().date).isNotNull
    }

    @Test
    fun `get returns the stored draft`() {
        val id = objectMapper.readTree(
            generate("""{"date":"2026-10-02"}""").andReturn().response.contentAsString,
        ).get("id").asLong()

        mockMvc.perform(get("/api/v1/workout-drafts/$id"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(id))
            .andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.status").value("DRAFT"))
    }

    @Test
    fun `an unknown draft id is a 404`() {
        mockMvc.perform(get("/api/v1/workout-drafts/999999"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("WORKOUT_DRAFT_NOT_FOUND"))
    }

    @Test
    fun `a revision creates version 2 and supersedes version 1`() {
        val id = objectMapper.readTree(
            generate("""{"date":"2026-10-02"}""").andReturn().response.contentAsString,
        ).get("id").asLong()

        mockMvc.perform(
            post("/api/v1/workout-drafts/$id/revisions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"request":"오늘 다리가 무거워. 인터벌 대신 지속주 형태로 바꿔줘."}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.version").value(2))
            .andExpect(jsonPath("$.status").value("DRAFT"))

        mockMvc.perform(get("/api/v1/workout-drafts/$id"))
            .andExpect(jsonPath("$.status").value("SUPERSEDED"))

        // the athlete's own words were handed to the coach verbatim, not pre-interpreted by Spring
        assertThat(coach.revisionRequests.single()).contains("인터벌 대신 지속주")
        assertThat(coach.revisedDrafts.single().version).isEqualTo(1)
    }

    @Test
    fun `revising an already superseded version is a 409`() {
        val id = objectMapper.readTree(
            generate("""{"date":"2026-10-02"}""").andReturn().response.contentAsString,
        ).get("id").asLong()
        mockMvc.perform(
            post("/api/v1/workout-drafts/$id/revisions")
                .contentType(MediaType.APPLICATION_JSON).content("""{"request":"shorter please"}"""),
        ).andExpect(status().isOk)

        mockMvc.perform(
            post("/api/v1/workout-drafts/$id/revisions")
                .contentType(MediaType.APPLICATION_JSON).content("""{"request":"shorter again"}"""),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("WORKOUT_DRAFT_SUPERSEDED"))
    }

    @Test
    fun `a blank revision request is rejected before any coach call`() {
        val id = objectMapper.readTree(
            generate("""{"date":"2026-10-02"}""").andReturn().response.contentAsString,
        ).get("id").asLong()
        val before = coach.revisionRequests.size

        mockMvc.perform(
            post("/api/v1/workout-drafts/$id/revisions")
                .contentType(MediaType.APPLICATION_JSON).content("""{"request":"   "}"""),
        ).andExpect(status().isBadRequest)

        assertThat(coach.revisionRequests).hasSize(before)
    }

    @Test
    fun `a negative availableMinutes is rejected`() {
        generate("""{"date":"2026-10-02","availableMinutes":-5}""").andExpect(status().isBadRequest)
    }

    @Test
    fun `a malformed date is rejected`() {
        generate("""{"date":"not-a-date"}""").andExpect(status().isBadRequest)
    }

    @Test
    fun `a coach provider failure maps to 503 and stores nothing`() {
        coach.failWith = AiCoachException(
            AiCoachException.Reason.PROVIDER_UNAVAILABLE, "Claude CLI not found",
        )

        generate("""{"date":"2026-10-02"}""")
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("AI_COACH_PROVIDER_UNAVAILABLE"))

        assertThat(repository.count()).isZero()
    }

    @Test
    fun `a coach timeout maps to 504`() {
        coach.failWith = AiCoachException(AiCoachException.Reason.TIMEOUT, "timed out")

        generate("""{"date":"2026-10-02"}""")
            .andExpect(status().isGatewayTimeout)
            .andExpect(jsonPath("$.code").value("AI_COACH_TIMEOUT"))
    }

    @Test
    fun `a validation failure maps to 422 and stores nothing`() {
        coach.failWith = AiCoachException(
            AiCoachException.Reason.VALIDATION_FAILED, "segment durations sum to 10 but total is 40",
        )

        generate("""{"date":"2026-10-02"}""")
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.code").value("AI_COACH_VALIDATION_FAILED"))

        assertThat(repository.count()).isZero()
    }

    @Test
    fun `an invalid AI response maps to 502`() {
        coach.failWith = AiCoachException(AiCoachException.Reason.INVALID_RESPONSE, "not JSON")

        generate("""{"date":"2026-10-02"}""")
            .andExpect(status().isBadGateway)
            .andExpect(jsonPath("$.code").value("AI_COACH_INVALID_RESPONSE"))
    }

    @Test
    fun `there is no approve or publish endpoint on the draft API`() {
        val id = objectMapper.readTree(
            generate("""{"date":"2026-10-02"}""").andReturn().response.contentAsString,
        ).get("id").asLong()

        mockMvc.perform(post("/api/v1/workout-drafts/$id/approve"))
            .andExpect { assertThat(it.response.status).isIn(404, 405) }
        mockMvc.perform(post("/api/v1/workout-drafts/$id/publish"))
            .andExpect { assertThat(it.response.status).isIn(404, 405) }
    }

    @Test
    fun `generating a draft with publishing enabled still never touches the publisher`() {
        generate("""{"date":"2026-10-02"}""").andExpect(status().isOk)

        // asserted again in cleanUp(), but stated here because it is the point of this phase
        verifyNoInteractions(publishService, publisher, intervalsClient)
    }
}
