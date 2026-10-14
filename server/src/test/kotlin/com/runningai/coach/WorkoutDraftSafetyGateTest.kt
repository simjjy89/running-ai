package com.runningai.coach

import com.fasterxml.jackson.databind.ObjectMapper
import com.runningai.athlete.AthleteService
import com.runningai.injury.InjuryReportEntity
import com.runningai.injury.InjuryReportRepository
import com.runningai.injury.InjuryStatus
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Clock
import java.time.Instant

/**
 * The Phase 7B-2 server-enforced injury/pain safety gate on AI-coach draft generation, proven
 * end-to-end: real [WorkoutSafetyGateService], real [com.runningai.injury.WorkoutSafetyPolicy], real
 * H2 schema - only the AI itself is faked, so a scripted "coach" can simulate ignoring the safety
 * constraint without needing a real, uncooperative LLM.
 *
 * The point of this suite is to prove the server's *own* independent block, never to accept "the
 * AI chose REST" as proof of safety.
 */
@SpringBootTest(
    properties = [
        "running-ai.intervals.api-key=",
        "running-ai.intervals.base-url=http://127.0.0.1:9",
    ],
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkoutDraftSafetyGateTest {

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
    @Autowired private lateinit var injuryReportRepository: InjuryReportRepository
    @Autowired private lateinit var athleteService: AthleteService
    @Autowired private lateinit var clock: Clock

    @BeforeEach
    fun reset() = coach.reset()

    @AfterEach
    fun cleanUp() {
        repository.deleteAll()
        injuryReportRepository.deleteAll()
    }

    private fun generate(body: String = """{"date":"2026-10-13"}""") =
        mockMvc.perform(post("/api/v1/workout-drafts").contentType(MediaType.APPLICATION_JSON).content(body))

    private fun report(
        status: InjuryStatus,
        painOnWalking: Boolean? = null,
        ageHours: Long = 0,
        athleteId: Long = athleteService.getDefaultAthlete().id,
    ) {
        val at = Instant.now(clock).minusSeconds(ageHours * 3600)
        injuryReportRepository.save(
            InjuryReportEntity(
                athleteId = athleteId, bodySite = "knee", side = null, painPresent = status == InjuryStatus.ACTIVE,
                painIntensity = null, painOnWalking = painOnWalking, painOnStairs = null, swelling = null,
                rangeOfMotionLimited = null, status = status, notes = null, reportedAt = at, recordedAt = at,
            ),
        )
    }

    @Test
    fun `no injury state ever recorded blocks a non-REST draft and stores nothing`() {
        generate()
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.code").value("WORKOUT_SAFETY_NO_INJURY_STATE_RECORDED"))

        assertThat(repository.count()).isZero()
    }

    @Test
    fun `an ACTIVE gait-pain report blocks a non-REST draft the coach proposes`() {
        report(InjuryStatus.ACTIVE, painOnWalking = true)
        coach.respondWith { CoachTestFixtures.draft(date = it.date, workoutType = "EASY") }

        generate()
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.code").value("WORKOUT_SAFETY_INJURY_ACTIVE"))

        assertThat(repository.count()).isZero()
    }

    @Test
    fun `an ACTIVE gait-pain report still allows a REST draft to be generated and stored`() {
        report(InjuryStatus.ACTIVE, painOnWalking = true)
        coach.respondWith { CoachTestFixtures.restDraft(date = it.date) }

        generate().andExpect(status().isOk).andExpect(jsonPath("$.workoutType").value("REST"))

        assertThat(repository.count()).isEqualTo(1)
    }

    @Test
    fun `the coach ignoring the safety constraint and returning a running workout is rejected (fail closed)`() {
        report(InjuryStatus.ACTIVE, painOnWalking = true)
        // Simulates an AI response that disregards the server-enforced constraint text entirely.
        coach.respondWith { CoachTestFixtures.draft(date = it.date, workoutType = "LONG_RUN") }

        generate()
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.code").value("WORKOUT_SAFETY_INJURY_ACTIVE"))

        assertThat(repository.count()).isZero()
    }

    @Test
    fun `a fresh explicit RESOLVED report allows normal draft generation`() {
        report(InjuryStatus.RESOLVED)
        coach.respondWith { CoachTestFixtures.draft(date = it.date, workoutType = "EASY") }

        generate().andExpect(status().isOk).andExpect(jsonPath("$.workoutType").value("EASY"))

        assertThat(repository.count()).isEqualTo(1)
    }

    @Test
    fun `the safety state is re-checked right before saving, not only before the coach call`() {
        // RESOLVED when the request starts, so the pre-call check and the prompt constraint allow a
        // normal session - but an ACTIVE report arrives while the (synchronous, here) coach call is
        // "in flight", simulating a real report landing during the several seconds a live AI call takes.
        report(InjuryStatus.RESOLVED)
        coach.respondWith {
            report(InjuryStatus.ACTIVE, painOnWalking = true)
            CoachTestFixtures.draft(date = it.date, workoutType = "EASY")
        }

        generate()
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.code").value("WORKOUT_SAFETY_INJURY_ACTIVE"))

        assertThat(repository.count()).isZero()
    }

    @Test
    fun `the stored structured injury status is injected ahead of this request's own free-text feedback`() {
        report(InjuryStatus.ACTIVE, painOnWalking = true)
        coach.respondWith { CoachTestFixtures.restDraft(date = it.date) }

        generate("""{"date":"2026-10-13","painOrFatigueFeedback":"I feel completely fine now"}""")
            .andExpect(status().isOk)

        val feedback = (coach.createdContexts.single() as TrainingContext).constraints.painOrFatigueFeedback
        requireNotNull(feedback)
        assertThat(feedback.indexOf("SERVER-ENFORCED SAFETY CONSTRAINT"))
            .isLessThan(feedback.indexOf("I feel completely fine now"))
    }

    /**
     * Phase 7F-3A. A RESOLVED report deliberately produces no safety constraint text, which is
     * exactly why the coach used to learn nothing about the injury at all. The structured injury
     * history now travels with the context regardless of what the gate decided.
     */
    @Test
    fun `a RESOLVED report reaches the coach as injury history even though the constraint text is silent`() {
        report(InjuryStatus.RESOLVED)
        coach.respondWith { CoachTestFixtures.draft(date = it.date, workoutType = "EASY") }

        generate().andExpect(status().isOk)

        val context = coach.createdContexts.single() as TrainingContext
        assertThat(context.constraints.painOrFatigueFeedback).isNull()
        val history = requireNotNull(context.injuryHistory)
        assertThat(requireNotNull(history.latest).status).isEqualTo(InjuryStatus.RESOLVED)
        assertThat(history.gate.nonRestTrainingAllowed).isTrue()
    }

    @Test
    fun `a RESOLVED report following a recent ACTIVE one tells the coach how long ago the injury was`() {
        report(InjuryStatus.ACTIVE, painOnWalking = true, ageHours = 49)
        report(InjuryStatus.RESOLVED)
        coach.respondWith { CoachTestFixtures.draft(date = it.date, workoutType = "EASY") }

        generate().andExpect(status().isOk)

        val history = requireNotNull((coach.createdContexts.single() as TrainingContext).injuryHistory)
        assertThat(history.daysSinceLatestActiveReport).isEqualTo(2)
        assertThat(history.previousReports.single().status).isEqualTo(InjuryStatus.ACTIVE)
    }

    /**
     * The injury history is information for the coach, never permission. A blocking gate still
     * blocks with the history present, and the history is still handed over for the REST session
     * the coach is allowed to design.
     */
    @Test
    fun `the injury history never widens the gate`() {
        report(InjuryStatus.ACTIVE, painOnWalking = true)
        coach.respondWith { CoachTestFixtures.draft(date = it.date, workoutType = "EASY") }

        generate()
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.code").value("WORKOUT_SAFETY_INJURY_ACTIVE"))

        val history = requireNotNull((coach.createdContexts.single() as TrainingContext).injuryHistory)
        assertThat(history.gate.nonRestTrainingAllowed).isFalse()
        assertThat(repository.count()).isZero()
    }
}
