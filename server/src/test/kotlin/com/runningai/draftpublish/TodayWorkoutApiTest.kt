package com.runningai.draftpublish

import com.runningai.athlete.AthleteService
import com.runningai.coach.CoachTestFixtures.draft
import com.runningai.coach.CoachTestFixtures.restDraft
import com.runningai.coach.CoachTestFixtures.segment
import com.runningai.coach.WorkoutDraft
import com.runningai.coach.WorkoutDraftApprovalRepository
import com.runningai.coach.WorkoutDraftRepository
import com.runningai.coach.WorkoutDraftStore
import com.runningai.injury.InjuryReportRepository
import com.runningai.injury.InjuryTestFixtures
import com.runningai.training.IntensityClass
import com.runningai.training.SegmentType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Phase 8B (Workstream D): the approved plan, in the shape a display can use.
 *
 * The watch face had no route to RunningAI. The relay asked Intervals.icu for calendar events and
 * read its REST decision out of the legacy `C:\running-ai\state\today_plan.json`, so an approved AI
 * draft reached the watch only if it had already been published, and a REST day the coach decided
 * never reached it at all. This endpoint is the missing statement: what the athlete approved.
 *
 * The distinction the tests care most about is REST versus NO_PLAN. "No events found" and "today is
 * a rest day" look identical to a calendar reader and mean opposite things to an athlete.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TodayWorkoutApiTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var store: WorkoutDraftStore
    @Autowired private lateinit var drafts: WorkoutDraftRepository
    @Autowired private lateinit var approvals: WorkoutDraftApprovalRepository
    @Autowired private lateinit var publications: WorkoutDraftPublicationRepository
    @Autowired private lateinit var injuryReportRepository: InjuryReportRepository
    @Autowired private lateinit var athleteService: AthleteService
    @Autowired private lateinit var clock: Clock

    private lateinit var today: LocalDate

    @BeforeEach
    fun setUp() {
        InjuryTestFixtures.seedResolved(injuryReportRepository, athleteService, clock)
        today = LocalDate.now(clock.withZone(ZoneId.of(athleteService.getDefaultAthlete().timezone)))
    }

    @AfterEach
    fun cleanUp() {
        publications.deleteAll()
        approvals.deleteAll()
        drafts.deleteAll()
        injuryReportRepository.deleteAll()
    }

    private fun approve(d: WorkoutDraft): Long {
        val id = requireNotNull(store.save(d, UUID.randomUUID().toString(), supersede = null).id)
        mockMvc.perform(post("/api/v1/workout-drafts/$id/approve")).andExpect(status().isOk)
        return id
    }

    private fun todayWorkout() = mockMvc.perform(get("/api/v1/today-workout"))

    @Test
    fun `with nothing approved the answer is NO_PLAN, not an error and not a rest day`() {
        todayWorkout()
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.date").value(today.toString()))
            .andExpect(jsonPath("$.status").value("NO_PLAN"))
            .andExpect(jsonPath("$.source").value("NONE"))
            .andExpect(jsonPath("$.workoutType").doesNotExist())
            .andExpect(jsonPath("$.published").value(false))
            .andExpect(jsonPath("$.stale").value(false))
    }

    @Test
    fun `an approved workout is reported with its type and duration`() {
        approve(draft(date = today, workoutType = "EASY", totalDurationMinutes = 40))

        todayWorkout()
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("HAS_PLAN"))
            .andExpect(jsonPath("$.source").value("APPROVED_DRAFT"))
            .andExpect(jsonPath("$.workoutType").value("EASY"))
            .andExpect(jsonPath("$.totalDurationMinutes").value(40))
            .andExpect(jsonPath("$.approvedAt").isNotEmpty)
    }

    /**
     * The case the legacy path got wrong. An approved REST day is a decision; an empty calendar is
     * an absence. Collapsing them tells the athlete "nothing planned" on a day the coach deliberately
     * prescribed rest.
     */
    @Test
    fun `an approved rest day is REST and is never confused with NO_PLAN`() {
        approve(restDraft(date = today))

        todayWorkout()
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("REST"))
            .andExpect(jsonPath("$.source").value("APPROVED_DRAFT"))
            .andExpect(jsonPath("$.workoutType").doesNotExist())
            .andExpect(jsonPath("$.totalDurationMinutes").doesNotExist())
    }

    /** The approval is what counts, not the publish: an unpublished plan is still the plan. */
    @Test
    fun `an approved but unpublished workout is still reported`() {
        approve(draft(date = today))

        todayWorkout()
            .andExpect(jsonPath("$.status").value("HAS_PLAN"))
            .andExpect(jsonPath("$.published").value(false))
            .andExpect(jsonPath("$.publishedAt").doesNotExist())
    }

    @Test
    fun `a repeated block reports its repetition count`() {
        approve(
            draft(
                date = today,
                workoutType = "INTERVAL",
                totalDurationMinutes = 60,
                segments = listOf(
                    segment(SegmentType.WARM_UP, 15, IntensityClass.VERY_EASY),
                    segment(SegmentType.MAIN, 3, IntensityClass.HARD, repetitions = 6, recoveryMinutes = 2),
                    segment(SegmentType.COOL_DOWN, 15, IntensityClass.VERY_EASY),
                ),
            ),
        )

        todayWorkout()
            .andExpect(jsonPath("$.workoutType").value("INTERVAL"))
            .andExpect(jsonPath("$.repetitions").value(6))
    }

    @Test
    fun `a workout with no repeated block reports no repetitions rather than one`() {
        approve(draft(date = today))

        todayWorkout().andExpect(jsonPath("$.repetitions").doesNotExist())
    }

    /** A plan for another day must be visibly another day's, never shown as today's. */
    @Test
    fun `asking about another date marks the answer stale and still reports the athlete's today`() {
        val tomorrow = today.plusDays(1)
        approve(draft(date = tomorrow))

        mockMvc.perform(get("/api/v1/today-workout").param("date", tomorrow.toString()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.date").value(tomorrow.toString()))
            .andExpect(jsonPath("$.status").value("HAS_PLAN"))
            .andExpect(jsonPath("$.stale").value(true))
            .andExpect(jsonPath("$.athleteToday").value(today.toString()))
    }

    /** Today's answer must not be reached by a plan approved for a different day. */
    @Test
    fun `a plan approved for another date does not become today's plan`() {
        approve(draft(date = today.plusDays(1)))

        todayWorkout().andExpect(jsonPath("$.status").value("NO_PLAN"))
    }

    /**
     * Free text must not cross into this contract. The consumer is a relay feeding a 14-glyph
     * pixel-font display, and a coach-written title is model-generated text.
     */
    @Test
    fun `no coach free text is exposed`() {
        approve(draft(date = today, title = "아주 긴 한국어 제목 with free text"))

        val body = todayWorkout().andReturn().response.contentAsString

        assertThat(body).doesNotContain("한국어").doesNotContain("free text")
        assertThat(body).doesNotContain("title").doesNotContain("rationale").doesNotContain("assessment")
    }

    /** Read-only and database-only: nothing it does can create or publish a workout. */
    @Test
    fun `reading the plan never creates a publication`() {
        approve(draft(date = today))

        todayWorkout().andExpect(status().isOk)
        todayWorkout().andExpect(status().isOk)

        assertThat(publications.count()).isZero()
    }

    @Test
    fun `a malformed date is a 400, not a server error`() {
        mockMvc.perform(get("/api/v1/today-workout").param("date", "tomorrow"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
    }
}
