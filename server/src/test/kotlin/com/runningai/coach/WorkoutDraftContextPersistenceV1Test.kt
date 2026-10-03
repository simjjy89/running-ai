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
 * Per-draft context snapshot persistence when the server is configured for V1 (Phase 6H-7 §73):
 * every new draft records which context version it saw, the exact canonical JSON and its SHA-256,
 * even while V1 is the active configuration.
 */
@SpringBootTest(
    properties = [
        "running-ai.coach.context-version=V1",
        "running-ai.intervals.api-key=",
        "running-ai.intervals.base-url=http://127.0.0.1:9",
    ],
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkoutDraftContextPersistenceV1Test {

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
    fun `a V1 draft persists its own context_version and a valid snapshot plus hash`() {
        val id = objectMapper.readTree(
            mockMvc.perform(post("/api/v1/workout-drafts").contentType(MediaType.APPLICATION_JSON)
                .content("""{"date":"2026-10-02"}"""))
                .andExpect(status().isOk).andReturn().response.contentAsString,
        ).get("id").asLong()

        val row = repository.findById(id).orElseThrow()
        assertThat(row.contextVersion).isEqualTo("V1")
        assertThat(row.contextSnapshot).isNotNull()
        assertThat(row.contextBuiltAt).isNotNull()
        assertThat(row.contextSha256).matches("[0-9a-f]{64}")
        // the stored tree really is the V1 shape (has recentTraining/weeklyContext, not V2's dataCoverage)
        assertThat(row.contextSnapshot!!.has("recentTraining")).isTrue()
        assertThat(row.contextSnapshot!!.has("dataCoverage")).isFalse()
    }
}
