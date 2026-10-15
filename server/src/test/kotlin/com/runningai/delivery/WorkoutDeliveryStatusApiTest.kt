package com.runningai.delivery

import com.fasterxml.jackson.databind.ObjectMapper
import com.runningai.athlete.AthleteService
import com.runningai.coach.CoachTestFixtures.draft
import com.runningai.coach.CoachTestFixtures.restDraft
import com.runningai.coach.WorkoutDraft
import com.runningai.coach.WorkoutDraftApprovalRepository
import com.runningai.coach.WorkoutDraftRepository
import com.runningai.coach.WorkoutDraftStore
import com.runningai.draftpublish.WorkoutDraftPublicationRepository
import com.runningai.draftpublish.WorkoutDraftPublishAttemptRepository
import com.runningai.injury.InjuryReportRepository
import com.runningai.injury.InjuryTestFixtures
import com.runningai.integration.garmin.GarminConnectorException
import com.runningai.integration.garmin.GarminScheduledWorkoutClient
import com.runningai.integration.intervals.IntervalsPublishOperation
import com.runningai.integration.intervals.IntervalsPublishResult
import com.runningai.integration.intervals.IntervalsWorkoutClient
import com.runningai.integration.intervals.IntervalsWorkoutPublisher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Clock
import java.time.YearMonth
import java.util.UUID

/**
 * Phase 8B (Workstream E): did the published workout actually get anywhere?
 *
 * RunningAI publishes to Intervals.icu and can prove that much - the publisher reads its own write
 * back. The hop from Intervals.icu to Garmin Connect is Intervals.icu's own integration, which this
 * repository neither performs nor controls, so until now the only evidence a workout reached the
 * watch was one person looking at an FR265 once.
 *
 * What these tests mostly protect is honesty about what is NOT known. Garmin's calendar service
 * returns only workouts already committed to the visible calendar, and an Intervals push that has
 * not happened yet looks exactly like one that never will - so an absence must never be reported
 * as a failure, and watch-level delivery, which no available API reports, must never be implied by
 * a PRESENT answer.
 */
@SpringBootTest(
    properties = [
        "running-ai.draft-publishing.enabled=true",
        "running-ai.intervals.api-key=",
        "running-ai.intervals.base-url=http://127.0.0.1:9",
    ],
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkoutDeliveryStatusApiTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var store: WorkoutDraftStore
    @Autowired private lateinit var drafts: WorkoutDraftRepository
    @Autowired private lateinit var approvals: WorkoutDraftApprovalRepository
    @Autowired private lateinit var publications: WorkoutDraftPublicationRepository
    @Autowired private lateinit var publishAttempts: WorkoutDraftPublishAttemptRepository
    @Autowired private lateinit var injuryReportRepository: InjuryReportRepository
    @Autowired private lateinit var athleteService: AthleteService
    @Autowired private lateinit var clock: Clock

    @MockitoBean private lateinit var publisher: IntervalsWorkoutPublisher
    @MockitoBean private lateinit var intervalsClient: IntervalsWorkoutClient
    @MockitoBean private lateinit var garmin: GarminScheduledWorkoutClient

    @BeforeEach
    fun seedSafeInjuryState() {
        InjuryTestFixtures.seedResolved(injuryReportRepository, athleteService, clock)
    }

    @AfterEach
    fun cleanUp() {
        publishAttempts.deleteAll()
        publications.deleteAll()
        approvals.deleteAll()
        drafts.deleteAll()
        injuryReportRepository.deleteAll()
    }

    // ---- helpers --------------------------------------------------------------------------------

    private fun approved(d: WorkoutDraft = draft()): Long {
        val id = requireNotNull(store.save(d, UUID.randomUUID().toString(), supersede = null).id)
        mockMvc.perform(post("/api/v1/workout-drafts/$id/approve")).andExpect(status().isOk)
        return id
    }

    private fun publishedDraft(): Long {
        val id = approved()
        doAnswer { IntervalsPublishResult(IntervalsPublishOperation.CREATED, "evt-1", true, it.getArgument(0)) }
            .`when`(publisher).publish(any(), any())
        mockMvc.perform(post("/api/v1/workout-drafts/$id/publish")).andExpect(status().isOk)
        return id
    }

    private fun deliveryStatus(id: Long, checkGarmin: Boolean? = null) =
        mockMvc.perform(
            get("/api/v1/workout-drafts/$id/delivery-status").apply {
                if (checkGarmin != null) param("checkGarmin", checkGarmin.toString())
            },
        )

    private fun garminCalendar(vararg items: String) {
        `when`(garmin.fetchMonth(any())).thenReturn(
            objectMapper.readTree("""{"calendarItems":[${items.joinToString(",")}]}"""),
        )
    }

    private fun workoutItem(date: String) =
        """{"itemType":"workout","date":"$date","title":"RunningAI workout","workoutId":1234,"sportTypeKey":"running"}"""

    // ---- Garmin present --------------------------------------------------------------------------

    @Test
    fun `a published workout that Garmin's calendar holds is reported present on both sides`() {
        val id = publishedDraft()
        garminCalendar(workoutItem("2026-10-02"))

        deliveryStatus(id)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.intervalsDelivery").value("PUBLISHED_AND_VERIFIED"))
            .andExpect(jsonPath("$.intervalsEventId").value("evt-1"))
            .andExpect(jsonPath("$.garminDelivery").value("GARMIN_PRESENT"))
            .andExpect(jsonPath("$.garminScheduledWorkoutCount").value(1))
            .andExpect(jsonPath("$.garminCheckedAt").isNotEmpty)
    }

    /**
     * The whole point of the endpoint: PRESENT on Garmin still says nothing about the watch, and
     * the answer says so rather than letting the reader assume.
     */
    @Test
    fun `delivery to the device is always reported as unknown, even when Garmin has the workout`() {
        val id = publishedDraft()
        garminCalendar(workoutItem("2026-10-02"))

        deliveryStatus(id).andExpect(jsonPath("$.deviceDelivery").value("UNKNOWN"))
    }

    // ---- Garmin absent, which is not failure -------------------------------------------------------

    @Test
    fun `an empty Garmin calendar is reported as not found, never as a failure`() {
        val id = publishedDraft()
        garminCalendar()

        deliveryStatus(id)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.garminDelivery").value("GARMIN_NOT_FOUND"))
            .andExpect(jsonPath("$.garminScheduledWorkoutCount").value(0))
            // Supplied so a two-minute-old publish can be told from a two-day-old one. Judging
            // that is a human's or the coach's job, never this service's.
            .andExpect(jsonPath("$.minutesSincePublish").isNumber)
    }

    @Test
    fun `a workout on another date in the same month is not counted as this draft's`() {
        val id = publishedDraft()
        garminCalendar(workoutItem("2026-10-09"))

        deliveryStatus(id).andExpect(jsonPath("$.garminDelivery").value("GARMIN_NOT_FOUND"))
    }

    @Test
    fun `a non-workout calendar item on the date is not counted as a scheduled workout`() {
        val id = publishedDraft()
        garminCalendar("""{"itemType":"activity","date":"2026-10-02","activityId":1}""")

        deliveryStatus(id).andExpect(jsonPath("$.garminDelivery").value("GARMIN_NOT_FOUND"))
    }

    // ---- Garmin unreachable --------------------------------------------------------------------

    /** A failed lookup is its own state: it says nothing about delivery, so it is not an absence. */
    @Test
    fun `a Garmin failure is reported as a failed lookup and still answers the Intervals half`() {
        val id = publishedDraft()
        doThrow(GarminConnectorException(GarminConnectorException.Reason.AUTH_REQUIRED, 401, "expired"))
            .`when`(garmin).fetchMonth(any())

        deliveryStatus(id)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.garminDelivery").value("GARMIN_LOOKUP_FAILED"))
            .andExpect(jsonPath("$.garminLookupFailureCode").value("GARMIN_AUTH_REQUIRED"))
            .andExpect(jsonPath("$.intervalsDelivery").value("PUBLISHED_AND_VERIFIED"))
    }

    @Test
    fun `a failed lookup carries a code and never an exception message`() {
        val id = publishedDraft()
        doThrow(GarminConnectorException(GarminConnectorException.Reason.RATE_LIMITED, 429, "token abc123 throttled"))
            .`when`(garmin).fetchMonth(any())

        val body = deliveryStatus(id).andReturn().response.contentAsString

        assertThat(body).contains("GARMIN_RATE_LIMITED").doesNotContain("abc123")
    }

    // ---- when Garmin must not be asked at all -----------------------------------------------------

    /** Nothing was sent, so there is nothing for Garmin to have received - and no request to spend. */
    @Test
    fun `an unpublished draft never costs a Garmin request`() {
        val id = approved()

        deliveryStatus(id)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.intervalsDelivery").value("NOT_PUBLISHED"))
            .andExpect(jsonPath("$.garminDelivery").value("GARMIN_NOT_CHECKED"))

        verifyNoInteractions(garmin)
    }

    /** A rest day is never sent anywhere, so Garmin is never asked about one. */
    @Test
    fun `an approved rest day reports that nothing was sent and asks Garmin nothing`() {
        val id = approved(restDraft())
        mockMvc.perform(post("/api/v1/workout-drafts/$id/publish")).andExpect(status().isOk)

        deliveryStatus(id)
            .andExpect(jsonPath("$.intervalsDelivery").value("REST_DAY_NOT_SENT"))
            .andExpect(jsonPath("$.garminDelivery").value("GARMIN_NOT_CHECKED"))

        verifyNoInteractions(garmin)
    }

    @Test
    fun `checkGarmin false answers from the database alone`() {
        val id = publishedDraft()

        deliveryStatus(id, checkGarmin = false)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.intervalsDelivery").value("PUBLISHED_AND_VERIFIED"))
            .andExpect(jsonPath("$.garminDelivery").value("GARMIN_NOT_CHECKED"))

        verifyNoInteractions(garmin)
    }

    @Test
    fun `the Garmin calendar is asked for the draft's own month, exactly once`() {
        val id = publishedDraft()
        garminCalendar(workoutItem("2026-10-02"))

        deliveryStatus(id).andExpect(status().isOk)

        verify(garmin).fetchMonth(YearMonth.of(2026, 10))
    }

    // ---- read-only -------------------------------------------------------------------------------

    /** Reading where a workout got to must never move it anywhere. */
    @Test
    fun `checking delivery never publishes anything`() {
        val id = approved()

        deliveryStatus(id).andExpect(status().isOk)
        deliveryStatus(id).andExpect(status().isOk)

        verify(publisher, never()).publish(any(), any())
        verifyNoInteractions(intervalsClient)
        assertThat(publications.count()).isZero()
    }

    @Test
    fun `an unapproved draft is a 409, not an empty delivery report`() {
        val id = requireNotNull(store.save(draft(), UUID.randomUUID().toString(), supersede = null).id)

        deliveryStatus(id)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("WORKOUT_DRAFT_APPROVAL_REQUIRED"))
    }

    @Test
    fun `an unknown draft is a 404`() {
        deliveryStatus(999999)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("WORKOUT_DRAFT_NOT_FOUND"))
    }
}
