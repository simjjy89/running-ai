package com.runningai.coach

import com.fasterxml.jackson.databind.ObjectMapper
import com.runningai.coach.CoachTestFixtures.draft
import com.runningai.integration.intervals.IntervalsWorkoutClient
import com.runningai.integration.intervals.IntervalsWorkoutPublisher
import com.runningai.integration.intervals.WorkoutPublishApplicationService
import com.runningai.recovery.RecoveryDailyValues
import com.runningai.recovery.RecoveryRepository
import com.runningai.recovery.RecoverySnapshotService
import java.time.LocalDate
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
    @Autowired private lateinit var validator: WorkoutDraftValidator
    @Autowired private lateinit var recoverySnapshots: RecoverySnapshotService
    @Autowired private lateinit var recoveryRepository: RecoveryRepository

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
        recoveryRepository.deleteAll()
        verifyNoInteractions(publishService, publisher, intervalsClient)
    }

    private fun generate(body: String) =
        mockMvc.perform(post("/api/v1/workout-drafts").contentType(MediaType.APPLICATION_JSON).content(body))

    private fun storeRecovery(day: LocalDate, hrv: Double?, rhr: Int?) =
        recoverySnapshots.upsert(day, RecoveryDailyValues(hrv, null, null, null, null, rhr, null, null, null, null, null, null))

    @Test
    fun `the coach receives the stored Garmin recovery context with its baseline`() {
        val day = LocalDate.of(2026, 10, 2)
        (1L..7L).forEach { storeRecovery(day.minusDays(it), hrv = 50.0, rhr = 50) }
        storeRecovery(day, hrv = 42.0, rhr = null)

        generate("""{"date":"2026-10-02"}""").andExpect(status().isOk)

        val recovery = coach.createdContexts.single().recovery
        assertThat(recovery.hrv!!.lastNightAvgMs.current).isEqualTo(42.0)
        assertThat(recovery.hrv!!.lastNightAvgMs.differencePercent).isEqualTo(-16.0)
        // no RHR today: yesterday's reading is passed with its age, not today's guess
        assertThat(recovery.restingHeartRate!!.bpm.ageDays).isEqualTo(1)
        // never ingested: still null
        assertThat(recovery.sleep).isNull()
        assertThat(recovery.bodyBattery).isNull()
        assertThat(recovery.stress).isNull()
    }

    @Test
    fun `the coach is told recovery is unknown when nothing is stored`() {
        generate("""{"date":"2026-10-02"}""").andExpect(status().isOk)

        assertThat(coach.createdContexts.single().recovery.anyAvailable).isFalse()
    }

    @Test
    fun `the recovery context endpoint shows exactly what the coach sees, nulls included`() {
        val day = LocalDate.of(2026, 10, 2)
        storeRecovery(day, hrv = 45.0, rhr = 51)

        mockMvc.perform(get("/api/v1/recovery-context").param("date", "2026-10-02"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.hrv.lastNightAvgMs.current").value(45.0))
            .andExpect(jsonPath("$.hrv.lastNightAvgMs.baselineStatus").value("INSUFFICIENT_DATA"))
            .andExpect(jsonPath("$.hrv.lastNightAvgMs.baseline").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.restingHeartRate.bpm.current").value(51.0))
            .andExpect(jsonPath("$.sleep").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.stress").value(org.hamcrest.Matchers.nullValue()))
    }

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

    // ---- REST (Phase 6F.1) --------------------------------------------------------------------

    /** Scripts the fake coach and runs the real validator on its answer, as ClaudeAiCoach does. */
    private fun coachAnswers(block: (TrainingContext) -> WorkoutDraft) =
        coach.respondWith { ctx ->
            block(ctx).also {
                try {
                    validator.validate(it, ctx.date, ctx.athlete)
                } catch (e: WorkoutDraftValidationException) {
                    throw AiCoachException(AiCoachException.Reason.VALIDATION_FAILED, e.message ?: "invalid", e)
                }
            }
        }

    private fun idOf(result: org.springframework.test.web.servlet.ResultActions): Long =
        objectMapper.readTree(result.andReturn().response.contentAsString).get("id").asLong()

    private fun revise(id: Long, request: String) = mockMvc.perform(
        post("/api/v1/workout-drafts/$id/revisions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(mapOf("request" to request))),
    )

    @Test
    fun `a rest day draft is generated, returned and readable like any other draft`() {
        coachAnswers { CoachTestFixtures.restDraft(date = it.date) }

        val id = idOf(
            generate("""{"date":"2026-10-02","painOrFatigueFeedback":"Exhausted, legs feel dead"}""")
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.workoutType").value("REST"))
                .andExpect(jsonPath("$.totalDurationMinutes").value(0))
                .andExpect(jsonPath("$.segments").isArray)
                .andExpect(jsonPath("$.segments").isEmpty)
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.assessment.selectedWorkoutType").value("REST"))
                .andExpect(jsonPath("$.assessment.rationale").isNotEmpty)
                .andExpect(jsonPath("$.rest").doesNotExist()),
        )

        mockMvc.perform(get("/api/v1/workout-drafts/$id"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.workoutType").value("REST"))
            .andExpect(jsonPath("$.segments").isEmpty)
    }

    @Test
    fun `a rest day can be revised into an easy run`() {
        coachAnswers { CoachTestFixtures.restDraft(date = it.date) }
        val v1 = idOf(generate("""{"date":"2026-10-02"}"""))

        coachAnswers {
            draft(date = it.date, title = "Easy 30", segments = listOf(
                CoachTestFixtures.segment(com.runningai.training.SegmentType.MAIN, 30,
                    com.runningai.training.IntensityClass.EASY),
            ))
        }
        revise(v1, "몸은 괜찮아졌어. 30분 easy로 바꿔줘")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.version").value(2))
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andExpect(jsonPath("$.workoutType").value("EASY"))
            .andExpect(jsonPath("$.totalDurationMinutes").value(30))

        mockMvc.perform(get("/api/v1/workout-drafts/$v1"))
            .andExpect(jsonPath("$.status").value("SUPERSEDED"))
            .andExpect(jsonPath("$.workoutType").value("REST"))
        // the coach saw the rest day it was asked to change
        assertThat(coach.revisedDrafts.single().isRest).isTrue()
    }

    @Test
    fun `an easy run can be revised into a rest day`() {
        coachAnswers { draft(date = it.date) }
        val v1 = idOf(generate("""{"date":"2026-10-02"}"""))

        coachAnswers { CoachTestFixtures.restDraft(date = it.date) }
        val v2 = idOf(
            revise(v1, "I am completely exhausted today")
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.workoutType").value("REST"))
                .andExpect(jsonPath("$.totalDurationMinutes").value(0))
                .andExpect(jsonPath("$.segments").isEmpty),
        )

        mockMvc.perform(get("/api/v1/workout-drafts/$v1")).andExpect(jsonPath("$.status").value("SUPERSEDED"))
        mockMvc.perform(get("/api/v1/workout-drafts/$v2")).andExpect(jsonPath("$.status").value("DRAFT"))
        assertThat(repository.count()).isEqualTo(2)
    }

    @Test
    fun `a padded rest day from the coach is refused and nothing is stored`() {
        coachAnswers {
            CoachTestFixtures.restDraft(date = it.date).copy(
                totalDurationMinutes = 10,
                segments = listOf(CoachTestFixtures.segment(com.runningai.training.SegmentType.MAIN, 10,
                    com.runningai.training.IntensityClass.VERY_EASY)),
            )
        }

        generate("""{"date":"2026-10-02"}""")
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.code").value("AI_COACH_VALIDATION_FAILED"))

        assertThat(repository.count()).isZero()
    }
}
