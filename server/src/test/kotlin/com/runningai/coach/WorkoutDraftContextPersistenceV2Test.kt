package com.runningai.coach

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Same audit trail when the server is configured for V2 (Phase 6H-7 §43-47, §71-72): the stored
 * snapshot has the V2 shape, and a revision records its own snapshot without touching the one
 * already stored on the version it revised.
 */
@SpringBootTest(
    properties = [
        "running-ai.coach.context-version=V2",
        "running-ai.intervals.api-key=",
        "running-ai.intervals.base-url=http://127.0.0.1:9",
    ],
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkoutDraftContextPersistenceV2Test {

    @TestConfiguration
    class FakeCoachConfig {
        @Bean @Primary fun fakeAiCoach(): FakeAiCoach = FakeAiCoach()
    }

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var repository: WorkoutDraftRepository
    @Autowired private lateinit var coach: FakeAiCoach

    @BeforeEach
    fun reset() = coach.reset()

    @AfterEach
    fun cleanUp() = repository.deleteAll()

    @Test
    fun `a V2 draft persists its own context_version, snapshot and a valid hash`() {
        val id = objectMapper.readTree(
            mockMvc.perform(post("/api/v1/workout-drafts").contentType(MediaType.APPLICATION_JSON)
                .content("""{"date":"2026-10-02"}"""))
                .andExpect(status().isOk).andReturn().response.contentAsString,
        ).get("id").asLong()

        val row = repository.findById(id).orElseThrow()
        assertThat(row.contextVersion).isEqualTo("V2")
        assertThat(row.contextSnapshot!!.has("dataCoverage")).isTrue()
        assertThat(row.contextSnapshot!!.has("recentTraining")).isFalse()
        assertThat(row.contextSnapshot!!.has("candidateTrainingTypes")).isFalse()
        assertThat(row.contextSha256).matches("[0-9a-f]{64}")
    }

    @Test
    fun `a revision records its own context snapshot without touching the superseded version's`() {
        val v1Id = objectMapper.readTree(
            mockMvc.perform(post("/api/v1/workout-drafts").contentType(MediaType.APPLICATION_JSON)
                .content("""{"date":"2026-10-02"}"""))
                .andExpect(status().isOk).andReturn().response.contentAsString,
        ).get("id").asLong()
        val v1Hash = repository.findById(v1Id).orElseThrow().contextSha256!!

        val revisionId = objectMapper.readTree(
            mockMvc.perform(post("/api/v1/workout-drafts/$v1Id/revisions").contentType(MediaType.APPLICATION_JSON)
                .content("""{"request":"make it shorter"}"""))
                .andExpect(status().isOk).andReturn().response.contentAsString,
        ).get("id").asLong()
        val revisionHash = repository.findById(revisionId).orElseThrow().contextSha256!!

        // both rows keep their own, independently recorded snapshot; the superseded row is untouched
        assertThat(repository.findById(v1Id).orElseThrow().contextSha256).isEqualTo(v1Hash)
        assertThat(revisionHash).isNotBlank()
    }
}
