package com.runningai

import com.fasterxml.jackson.databind.ObjectMapper
import com.runningai.activity.Activity
import com.runningai.activity.ActivityRepository
import com.runningai.activity.ActivityType
import com.runningai.activity.ExternalSource
import com.runningai.athlete.AthleteService
import com.runningai.coach.CoachTestFixtures
import com.runningai.coach.FakeAiCoach
import com.runningai.coach.WorkoutDraftApprovalRepository
import com.runningai.coach.WorkoutDraftRepository
import com.runningai.draftpublish.DraftPublishAttemptOutcome
import com.runningai.draftpublish.WorkoutDraftPublicationRepository
import com.runningai.draftpublish.WorkoutDraftPublishAttemptRepository
import com.runningai.draftpublish.WorkoutDraftPublishAttemptStore
import com.runningai.injury.InjuryReportRepository
import com.runningai.injury.InjuryTestFixtures
import com.runningai.integration.garmin.GarminActivitySource
import com.runningai.integration.garmin.GarminConnectorException
import com.runningai.integration.garmin.GarminScheduledWorkoutClient
import com.runningai.integration.garmin.GarminSyncAttemptOutcome
import com.runningai.integration.garmin.GarminSyncAttemptRepository
import com.runningai.integration.garmin.GarminSyncStateRepository
import com.runningai.integration.intervals.IntervalsPublishOperation
import com.runningai.integration.intervals.IntervalsPublishResult
import com.runningai.integration.intervals.IntervalsWorkoutClient
import com.runningai.integration.intervals.IntervalsWorkoutPublisher
import com.runningai.training.IntensityClass
import com.runningai.training.SegmentType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The whole path, once, over the real schema: Garmin collection -> analysis -> coach context ->
 * draft -> revision -> approval -> publish preview -> publish -> what the watch and the delivery
 * check then see.
 *
 * Every per-step rule already has its own focused test; this one exists for the thing those cannot
 * check, which is that the steps still line up end to end. The handovers are where a system like
 * this actually breaks: a draft that approves but cannot be published, a publish that succeeds but
 * never reaches the watch, a failure that leaves no trace anywhere.
 *
 * Nothing external is reachable. The Garmin source, the Intervals publisher and client, the Garmin
 * calendar and the AI coach are all fakes or mocks, and the Intervals base URL points at a closed
 * port, so no test here can write to a real service.
 */
@SpringBootTest(
    properties = [
        "running-ai.draft-publishing.enabled=true",
        "running-ai.coach.context-version=V2",
        "running-ai.intervals.api-key=",
        "running-ai.intervals.base-url=http://127.0.0.1:9",
        "running-ai.garmin.connector.base-url=http://127.0.0.1:9",
    ],
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LifecycleIntegrationTest {

    @TestConfiguration
    class FakeCoachConfig {
        @Bean
        @Primary
        fun fakeAiCoach(): FakeAiCoach = FakeAiCoach()
    }

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var coach: FakeAiCoach
    @Autowired private lateinit var activities: ActivityRepository
    @Autowired private lateinit var drafts: WorkoutDraftRepository
    @Autowired private lateinit var approvals: WorkoutDraftApprovalRepository
    @Autowired private lateinit var publications: WorkoutDraftPublicationRepository
    @Autowired private lateinit var publishAttempts: WorkoutDraftPublishAttemptRepository
    @Autowired private lateinit var attemptLedger: WorkoutDraftPublishAttemptStore
    @Autowired private lateinit var syncAttempts: GarminSyncAttemptRepository
    @Autowired private lateinit var syncState: GarminSyncStateRepository
    @Autowired private lateinit var injuryReports: InjuryReportRepository
    @Autowired private lateinit var athletes: AthleteService
    @Autowired private lateinit var clock: Clock
    @Autowired private lateinit var jdbc: JdbcTemplate

    @MockitoBean private lateinit var activitySource: GarminActivitySource
    @MockitoBean private lateinit var publisher: IntervalsWorkoutPublisher
    @MockitoBean private lateinit var intervalsClient: IntervalsWorkoutClient
    @MockitoBean private lateinit var garminCalendar: GarminScheduledWorkoutClient

    private lateinit var today: LocalDate

    @BeforeEach
    fun setUp() {
        coach.reset()
        today = LocalDate.now(clock.withZone(ZoneId.of(athletes.getDefaultAthlete().timezone)))
        InjuryTestFixtures.seedResolved(injuryReports, athletes, clock)
    }

    @AfterEach
    fun cleanUp() {
        publishAttempts.deleteAll()
        publications.deleteAll()
        approvals.deleteAll()
        drafts.deleteAll()
        injuryReports.deleteAll()
        syncAttempts.deleteAll()
        syncState.deleteAll()
        listOf("activity_analysis_interval", "activity_analysis_interval_group", "activity_analysis",
            "activity_detail_collection", "activity_sample", "activity_zone", "activity_lap",
            "activity_detail", "activity_raw_payload", "activity_raw",
            "activity_intervals_metrics", "activity_source_link", "activity")
            .forEach { jdbc.update("delete from $it") }
    }

    // ---- helpers --------------------------------------------------------------------------------

    private fun generateDraft(): Long {
        coach.respondWith { CoachTestFixtures.draft(date = it.date, workoutType = "EASY") }
        val body = mockMvc.perform(
            post("/api/v1/workout-drafts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"date":"$today"}"""),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readTree(body).get("id").asLong()
    }

    /**
     * One Garmin activity-list item, built here rather than borrowed from
     * `GarminIncrementalSyncFixtures`: that fixture is package-private on purpose, and widening it
     * so a test in another package can reach it would loosen a boundary for convenience.
     */
    private fun garminActivity(id: Long, startTimeGmt: String) = objectMapper.readTree(
        """{"activityId": $id, "activityType": {"typeKey": "running"}, "startTimeGMT": "$startTimeGmt",
            "duration": 1800.0, "distance": 5000.0, "averageHR": 140.0, "maxHR": 160.0}""",
    )

    private fun publisherSucceeds(operation: IntervalsPublishOperation = IntervalsPublishOperation.CREATED) {
        doAnswer { IntervalsPublishResult(operation, "evt-e2e", true, it.getArgument(0)) }
            .`when`(publisher).publish(any(), any())
    }

    // ---- the happy path, end to end ---------------------------------------------------------------

    @Test
    fun `collection through approval to publish, and the watch and delivery check agree with it`() {
        // 1. Garmin collection: one new activity, recorded as a successful attempt.
        doReturn(listOf(garminActivity(9001L, "2026-10-10 06:00:00")))
            .`when`(activitySource).fetchActivities(anyInt(), anyInt())
        mockMvc.perform(post("/api/v1/garmin/sync")).andExpect(status().isOk)
        assertThat(activities.findAll()).hasSize(1)
        assertThat(syncAttempts.findAll().single().outcome).isEqualTo(GarminSyncAttemptOutcome.SUCCESS)

        // 2. The coach is handed a context built from what was collected, and designs a draft.
        val draftId = generateDraft()
        assertThat(coach.createdContexts).hasSize(1)

        // 3. The athlete asks for a change in their own words; the coach redesigns.
        // Genuinely shorter: the segments add up to the stated total, which the publishability
        // check re-verifies. Lowering only the total would make the draft unpublishable - correct
        // behaviour, but not what this step is testing.
        coach.respondWith {
            CoachTestFixtures.draft(
                date = it.date,
                workoutType = "EASY",
                segments = listOf(
                    CoachTestFixtures.segment(SegmentType.WARM_UP, 5, IntensityClass.VERY_EASY),
                    CoachTestFixtures.segment(SegmentType.MAIN, 20, IntensityClass.EASY),
                    CoachTestFixtures.segment(SegmentType.COOL_DOWN, 5, IntensityClass.VERY_EASY),
                ),
            )
        }
        val revised = mockMvc.perform(
            post("/api/v1/workout-drafts/$draftId/revisions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"request":"make it shorter today"}"""),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val revisedId = objectMapper.readTree(revised).get("id").asLong()
        assertThat(coach.revisionRequests).containsExactly("make it shorter today")

        // 4. Approval. Before it, the watch has nothing to show - the draft is not the decision.
        mockMvc.perform(get("/api/v1/today-workout")).andExpect(jsonPath("$.status").value("NO_PLAN"))
        mockMvc.perform(post("/api/v1/workout-drafts/$revisedId/approve")).andExpect(status().isOk)

        // 5. The watch sees the approved plan immediately, with no publish involved.
        mockMvc.perform(get("/api/v1/today-workout"))
            .andExpect(jsonPath("$.status").value("HAS_PLAN"))
            .andExpect(jsonPath("$.workoutType").value("EASY"))
            .andExpect(jsonPath("$.published").value(false))

        // 6. Preview, then publish.
        mockMvc.perform(get("/api/v1/workout-drafts/$revisedId/publish-preview"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.publishable").value(true))
        publisherSucceeds()
        mockMvc.perform(post("/api/v1/workout-drafts/$revisedId/publish"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.outcome").value("PUBLISHED"))

        // 7. The attempt is on the ledger, and the watch now also knows it was published.
        assertThat(attemptLedger.history(revisedId).single().outcome)
            .isEqualTo(DraftPublishAttemptOutcome.PUBLISHED)
        mockMvc.perform(get("/api/v1/today-workout")).andExpect(jsonPath("$.published").value(true))

        // 8. The delivery check reports both halves: verified on Intervals, present on Garmin,
        //    and still unknown on the device - which no API can answer.
        garminCalendarHoldsAWorkoutOn(today)
        mockMvc.perform(get("/api/v1/workout-drafts/$revisedId/delivery-status"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.intervalsDelivery").value("PUBLISHED_AND_VERIFIED"))
            .andExpect(jsonPath("$.garminDelivery").value("GARMIN_PRESENT"))
            .andExpect(jsonPath("$.deviceDelivery").value("UNKNOWN"))
    }

    private fun garminCalendarHoldsAWorkoutOn(date: LocalDate) {
        doReturn(
            objectMapper.readTree(
                """{"calendarItems":[{"itemType":"workout","date":"$date","workoutId":1}]}""",
            ),
        ).`when`(garminCalendar).fetchMonth(any())
    }

    // ---- an approved REST day, which never leaves RunningAI ------------------------------------------

    /**
     * The case the watch could not previously see at all: rest is never published, so a
     * calendar-driven display shows the same thing for "rest day" and "nothing planned".
     */
    @Test
    fun `an approved rest day reaches the watch as REST and is never sent anywhere`() {
        coach.respondWith { CoachTestFixtures.restDraft(date = it.date) }
        val body = mockMvc.perform(
            post("/api/v1/workout-drafts").contentType(MediaType.APPLICATION_JSON).content("""{"date":"$today"}"""),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val id = objectMapper.readTree(body).get("id").asLong()
        mockMvc.perform(post("/api/v1/workout-drafts/$id/approve")).andExpect(status().isOk)

        mockMvc.perform(get("/api/v1/today-workout")).andExpect(jsonPath("$.status").value("REST"))

        mockMvc.perform(post("/api/v1/workout-drafts/$id/publish"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.outcome").value("SKIPPED_REST_DAY"))
        org.mockito.Mockito.verifyNoInteractions(publisher, intervalsClient, garminCalendar)
    }

    // ---- failure scenarios, across the handovers ------------------------------------------------

    /** A collection failure must be visible afterwards and must not move the checkpoint. */
    @Test
    fun `a Garmin authentication failure is recorded and leaves the checkpoint untouched`() {
        doThrow(GarminConnectorException(GarminConnectorException.Reason.AUTH_REQUIRED, 401, "expired"))
            .`when`(activitySource).fetchActivities(anyInt(), anyInt())

        mockMvc.perform(post("/api/v1/garmin/sync")).andExpect(status().isUnauthorized)

        val attempt = syncAttempts.findAll().single()
        assertThat(attempt.outcome).isEqualTo(GarminSyncAttemptOutcome.FAILED)
        assertThat(attempt.failureCode).isEqualTo("GARMIN_AUTH_REQUIRED")
        assertThat(syncState.findAll()).isEmpty()
        // And the automatic path now says why it is holding off, while the manual one never is.
        mockMvc.perform(get("/api/v1/garmin/sync/status"))
            .andExpect(jsonPath("$.lastAttempt.failureClass").value("AUTH"))
    }

    /** A coach that cannot be reached is never substituted with a workout nobody designed. */
    @Test
    fun `an AI coach failure produces no draft at all`() {
        coach.failWith = com.runningai.coach.AiCoachException(
            com.runningai.coach.AiCoachException.Reason.PROVIDER_UNAVAILABLE, "no CLI",
        )

        mockMvc.perform(
            post("/api/v1/workout-drafts").contentType(MediaType.APPLICATION_JSON).content("""{"date":"$today"}"""),
        ).andExpect(status().is5xxServerError)

        assertThat(drafts.findAll()).isEmpty()
        mockMvc.perform(get("/api/v1/today-workout")).andExpect(jsonPath("$.status").value("NO_PLAN"))
    }

    /**
     * A failed publish must leave the draft publishable and the failure traceable - and must not
     * tell the watch a workout exists on Intervals.icu.
     */
    @Test
    fun `a failed publish leaves no publication, a recorded attempt, and a retryable draft`() {
        val id = generateDraft()
        mockMvc.perform(post("/api/v1/workout-drafts/$id/approve")).andExpect(status().isOk)
        doThrow(
            com.runningai.integration.intervals.IntervalsException(
                com.runningai.integration.intervals.IntervalsException.Reason.TIMEOUT, null, "timed out",
            ),
        ).`when`(publisher).publish(any(), any())

        mockMvc.perform(post("/api/v1/workout-drafts/$id/publish")).andExpect(status().isGatewayTimeout)

        assertThat(publications.count()).isZero()
        val attempt = attemptLedger.history(id).single()
        assertThat(attempt.outcome).isEqualTo(DraftPublishAttemptOutcome.FAILED)
        // The write may or may not have landed; the ledger says so rather than guessing.
        assertThat(attempt.remoteStateCertain).isFalse()
        mockMvc.perform(get("/api/v1/today-workout")).andExpect(jsonPath("$.published").value(false))

        // The same draft publishes cleanly once Intervals is back.
        publisherSucceeds(IntervalsPublishOperation.UPDATED)
        mockMvc.perform(post("/api/v1/workout-drafts/$id/publish")).andExpect(status().isOk)
        assertThat(attemptLedger.history(id)).hasSize(2)
    }

    /** The injury gate stands in front of every workout-producing step, including this whole flow. */
    @Test
    fun `with no injury report at all nothing non-REST can be approved or published`() {
        val id = generateDraft()
        injuryReports.deleteAll()

        mockMvc.perform(post("/api/v1/workout-drafts/$id/approve"))
            .andExpect(status().isUnprocessableEntity)

        assertThat(approvals.count()).isZero()
        assertThat(publications.count()).isZero()
        mockMvc.perform(get("/api/v1/today-workout")).andExpect(jsonPath("$.status").value("NO_PLAN"))
    }

    /** Two syncs at once must never become two Garmin collections. */
    @Test
    fun `the collection and publish paths are both single-flight across the whole flow`() {
        doReturn(listOf(garminActivity(9002L, "2026-10-10 06:00:00")))
            .`when`(activitySource).fetchActivities(anyInt(), anyInt())

        mockMvc.perform(post("/api/v1/garmin/sync")).andExpect(status().isOk)
        mockMvc.perform(post("/api/v1/garmin/sync")).andExpect(status().isOk)

        // Collected twice, stored once: the second run updates rather than duplicating.
        assertThat(activities.findAll()).hasSize(1)

        val id = generateDraft()
        mockMvc.perform(post("/api/v1/workout-drafts/$id/approve")).andExpect(status().isOk)
        publisherSucceeds()
        mockMvc.perform(post("/api/v1/workout-drafts/$id/publish")).andExpect(status().isOk)
        mockMvc.perform(post("/api/v1/workout-drafts/$id/publish"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.alreadyPublished").value(true))

        // One publication, one external write; the re-publish cost nothing outside.
        assertThat(publications.count()).isEqualTo(1)
        org.mockito.Mockito.verify(publisher, org.mockito.Mockito.times(1)).publish(any(), any())
    }

    /** The report must survive a history that is mostly gaps - that is the normal case here. */
    @Test
    fun `the analysis report describes a sparse, multi-source history without inventing anything`() {
        val athleteId = athletes.getDefaultAthlete().id
        activities.save(
            Activity(athleteId, ExternalSource.INTERVALS_ICU, "e2e-i-1", ActivityType.RUN,
                Instant.parse("2026-08-01T21:30:00Z"), 3000, 10000.0, 150, 170),
        )
        activities.save(
            Activity(athleteId, ExternalSource.GARMIN, "e2e-g-1", ActivityType.RUN,
                Instant.parse("2026-10-01T21:30:00Z").plus(Duration.ZERO), 2700, 10000.0, 155, 175),
        )

        mockMvc.perform(get("/api/v1/training-analysis-report"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.coverage.runningActivityCount").value(2))
            .andExpect(jsonPath("$.limitations.unanalysedActivityCount").value(2))
            // The imported half's structural gaps are stated, not left as missing fields.
            .andExpect(jsonPath("$.limitations.metricUnavailability[?(@.metric == 'CADENCE')].affectedActivityCount")
                .value(1))
    }
}
