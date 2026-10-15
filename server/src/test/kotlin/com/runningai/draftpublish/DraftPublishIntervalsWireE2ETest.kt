package com.runningai.draftpublish

import com.runningai.athlete.AthleteService
import com.runningai.coach.CoachTestFixtures.draft
import com.runningai.coach.CoachTestFixtures.segment
import com.runningai.coach.WorkoutDraft
import com.runningai.coach.WorkoutDraftApprovalRepository
import com.runningai.coach.WorkoutDraftRepository
import com.runningai.coach.WorkoutDraftStore
import com.runningai.injury.InjuryReportRepository
import com.runningai.injury.InjuryTestFixtures
import com.runningai.training.IntensityClass
import com.runningai.training.PrimaryTargetType
import com.runningai.training.SegmentType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withException
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath as responseJsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.client.RestClient
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.LocalDate
import java.util.Base64
import java.util.UUID

/**
 * Phase 7F-2A: the approved-draft publishing path end to end **down to the actual HTTP bytes**.
 *
 * [DraftPublishApiTest] mocks [com.runningai.integration.intervals.IntervalsWorkoutPublisher] and the
 * transport, so it proves the lifecycle but never the wire. This suite is the complement: the real
 * publisher, the real [com.runningai.integration.intervals.HttpIntervalsWorkoutClient] and the real
 * renderer run, and the only thing replaced is the socket - the `intervalsRestClient` bean is bound to
 * a [MockRestServiceServer], so every request the system would really send is asserted here and none
 * of them can leave the JVM.
 *
 * What it pins, for the first real external publish:
 *  - the exact request sequence of a publish (list by date -> create -> read back) and that a preview
 *    sends none of them;
 *  - the exact JSON RunningAI would PUT on the athlete's real calendar, including the ownership
 *    marker, the athlete the request is addressed to and the local date;
 *  - that a second publish of the same draft reaches Intervals.icu zero times;
 *  - that a foreign event on the date stops the publish before any write;
 *  - that a create whose answer is lost is resolved by looking the date up again, never by a second POST;
 *  - that the injury gate and the publishing switch both act before the first byte is sent;
 *  - that no credential ever reaches an API response.
 *
 * The API key here is synthetic and pinned as an inlined test property, which outranks any
 * `INTERVALS_API_KEY` in the developer's environment; the base URL is a reserved-looking test host that
 * the mock transport never resolves.
 */
@SpringBootTest(
    properties = [
        "running-ai.draft-publishing.enabled=true",
        "running-ai.workout-publishing.enabled=false",
        "running-ai.intervals.api-key=wire-test-key-not-real",
        "running-ai.intervals.athlete-id=0",
        "running-ai.intervals.base-url=https://intervals.test",
        "spring.main.allow-bean-definition-overriding=true",
    ],
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DraftPublishIntervalsWireE2ETest {

    /**
     * Replaces only the Intervals transport, by bean name. Deliberately not `@Primary`: the Garmin
     * connector has its own `RestClient` bean and must keep resolving to it.
     */
    @TestConfiguration
    class MockIntervalsTransport {

        @Bean
        fun intervalsMockTransport(): MockTransport {
            val builder = RestClient.builder().baseUrl(BASE_URL)
            val server = MockRestServiceServer.bindTo(builder).build()
            return MockTransport(server, builder.build())
        }

        @Bean("intervalsRestClient")
        fun intervalsRestClient(transport: MockTransport): RestClient = transport.restClient
    }

    class MockTransport(val server: MockRestServiceServer, val restClient: RestClient)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var transport: MockTransport
    @Autowired private lateinit var store: WorkoutDraftStore
    @Autowired private lateinit var drafts: WorkoutDraftRepository
    @Autowired private lateinit var approvals: WorkoutDraftApprovalRepository
    @Autowired private lateinit var publications: WorkoutDraftPublicationRepository
    @Autowired private lateinit var publishAttempts: WorkoutDraftPublishAttemptRepository
    @Autowired private lateinit var injuryReports: InjuryReportRepository
    @Autowired private lateinit var athleteService: AthleteService
    @Autowired private lateinit var clock: Clock

    private val server: MockRestServiceServer get() = transport.server

    @BeforeEach
    fun setUp() {
        server.reset()
        InjuryTestFixtures.seedResolved(injuryReports, athleteService, clock)
    }

    @AfterEach
    fun tearDown() {
        server.reset()
        publishAttempts.deleteAll()
        publications.deleteAll()
        approvals.deleteAll()
        drafts.deleteAll()
        injuryReports.deleteAll()
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private fun saved(d: WorkoutDraft = recoveryDraft()): Long =
        requireNotNull(store.save(d, UUID.randomUUID().toString(), supersede = null).id)

    private fun approve(id: Long): ResultActions = mockMvc.perform(post("/api/v1/workout-drafts/$id/approve"))
    private fun preview(id: Long): ResultActions = mockMvc.perform(get("/api/v1/workout-drafts/$id/publish-preview"))
    private fun publish(id: Long): ResultActions = mockMvc.perform(post("/api/v1/workout-drafts/$id/publish"))

    private fun approved(d: WorkoutDraft = recoveryDraft()): Long = saved(d).also { approve(it).andExpect(status().isOk) }

    /** Expects the one-day WORKOUT listing and answers with [events] (raw Intervals JSON objects). */
    private fun expectList(vararg events: String) {
        server.expect(ExpectedCount.once(), requestTo("$EVENTS?oldest=$DATE&newest=$DATE&category=WORKOUT"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.AUTHORIZATION, BASIC))
            .andRespond(withSuccess("[${events.joinToString(",")}]", MediaType.APPLICATION_JSON))
    }

    private fun expectReadback(eventId: String, body: String) {
        server.expect(ExpectedCount.once(), requestTo("$EVENTS/$eventId"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.AUTHORIZATION, BASIC))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON))
    }

    private fun ownedEvent(id: String, text: String) = event(id, text, MARKER)

    private fun event(id: String, text: String, externalId: String?) = """
        {"id":"$id","start_date_local":"${DATE}T00:00:00","category":"WORKOUT","type":"Run",
         "name":"RunningAI workout","description":${quote(text)},
         "external_id":${externalId?.let { quote(it) } ?: "null"}}
    """.trimIndent()

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    // ---- the sequence ----------------------------------------------------------------------------

    @Test
    fun `a preview sends nothing and a publish sends exactly list, create and readback`() {
        val id = approved()

        preview(id)
            .andExpect(status().isOk)
            .andExpect(responseJsonPath("$.externalWriteRequired").value(true))
            .andExpect(responseJsonPath("$.expectedOutcome").value("PUBLISH"))
            .andExpect(responseJsonPath("$.renderedWorkoutText").value(RENDERED))
        server.verify()     // a preview is read-only: not one Intervals request

        expectList()
        server.expect(ExpectedCount.once(), requestTo(EVENTS))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, BASIC))
            .andExpect(jsonPath("$.category").value("WORKOUT"))
            .andExpect(jsonPath("$.type").value("Run"))
            .andExpect(jsonPath("$.name").value("RunningAI workout"))
            .andExpect(jsonPath("$.start_date_local").value("${DATE}T00:00:00"))
            .andExpect(jsonPath("$.external_id").value(MARKER))
            .andExpect(jsonPath("$.description").value(RENDERED))
            .andRespond(withSuccess(ownedEvent(EVENT_ID, RENDERED), MediaType.APPLICATION_JSON))
        expectReadback(EVENT_ID, ownedEvent(EVENT_ID, RENDERED))

        publish(id)
            .andExpect(status().isOk)
            .andExpect(responseJsonPath("$.outcome").value("PUBLISHED"))
            .andExpect(responseJsonPath("$.intervalsOperation").value("CREATED"))
            .andExpect(responseJsonPath("$.remoteEventId").value(EVENT_ID))
            .andExpect(responseJsonPath("$.verified").value(true))
            .andExpect(responseJsonPath("$.alreadyPublished").value(false))
        server.verify()
    }

    @Test
    fun `the preview text is byte-identical to the description that is actually sent`() {
        val id = approved()
        val previewed = preview(id).andReturn().response.contentAsString
            .let { com.fasterxml.jackson.databind.ObjectMapper().readTree(it)["renderedWorkoutText"].asText() }

        expectList()
        server.expect(ExpectedCount.once(), requestTo(EVENTS))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.description").value(previewed))
            .andRespond(withSuccess(ownedEvent(EVENT_ID, previewed), MediaType.APPLICATION_JSON))
        expectReadback(EVENT_ID, ownedEvent(EVENT_ID, previewed))

        publish(id).andExpect(status().isOk)
        server.verify()
    }

    @Test
    fun `the request is addressed to the configured athlete and the marker names that athlete and date only`() {
        assertThat(EVENTS).isEqualTo("$BASE_URL/api/v1/athlete/0/events")
        assertThat(MARKER).isEqualTo("runningai:workout:v1:0:$DATE")

        val id = approved()
        expectList()
        server.expect(ExpectedCount.once(), requestTo(EVENTS))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.external_id").value("runningai:workout:v1:0:$DATE"))
            .andRespond(withSuccess(ownedEvent(EVENT_ID, RENDERED), MediaType.APPLICATION_JSON))
        expectReadback(EVENT_ID, ownedEvent(EVENT_ID, RENDERED))

        publish(id).andExpect(status().isOk)
        server.verify()
    }

    // ---- idempotency and conflicts ---------------------------------------------------------------

    @Test
    fun `a second publish of the same draft reaches Intervals zero times`() {
        val id = approved()
        expectList()
        server.expect(ExpectedCount.once(), requestTo(EVENTS))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(ownedEvent(EVENT_ID, RENDERED), MediaType.APPLICATION_JSON))
        expectReadback(EVENT_ID, ownedEvent(EVENT_ID, RENDERED))
        publish(id).andExpect(status().isOk)
        server.verify()

        server.reset()      // any further request would now be an unexpected one
        publish(id)
            .andExpect(status().isOk)
            .andExpect(responseJsonPath("$.alreadyPublished").value(true))
            .andExpect(responseJsonPath("$.remoteEventId").value(EVENT_ID))
        server.verify()
        assertThat(publications.findAll()).hasSize(1)
    }

    @Test
    fun `an identical workout already on the calendar is NO_CHANGE with no write at all`() {
        val id = approved()
        expectList(ownedEvent(EVENT_ID, RENDERED))

        publish(id)
            .andExpect(status().isOk)
            .andExpect(responseJsonPath("$.intervalsOperation").value("NO_CHANGE"))
            .andExpect(responseJsonPath("$.verified").value(true))
        server.verify()
    }

    @Test
    fun `a workout RunningAI does not own blocks the publish before any write`() {
        val id = approved()
        expectList(event("someone-elses-event", "- Coach-planned session", "garmin:12345"))

        publish(id)
            .andExpect(status().isConflict)
            .andExpect(responseJsonPath("$.code").value("INTERVALS_UNMANAGED_WORKOUT_CONFLICT"))
        server.verify()
        assertThat(publications.findAll()).isEmpty()
    }

    @Test
    fun `a create whose answer is lost is resolved by a second lookup, never by a second POST`() {
        val id = approved()
        expectList()
        server.expect(ExpectedCount.once(), requestTo(EVENTS))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withException(SocketTimeoutException("Read timed out")))
        // The POST had in fact been applied: the recovery lookup finds the event and adopts it.
        expectList(ownedEvent(EVENT_ID, RENDERED))
        expectReadback(EVENT_ID, ownedEvent(EVENT_ID, RENDERED))

        publish(id)
            .andExpect(status().isOk)
            .andExpect(responseJsonPath("$.intervalsOperation").value("CREATED"))
            .andExpect(responseJsonPath("$.remoteEventId").value(EVENT_ID))
        server.verify()     // exactly one POST was ever sent
        assertThat(publications.findAll()).hasSize(1)
    }

    @Test
    fun `a lost create that did not apply stores nothing and the retry publishes once`() {
        val id = approved()
        expectList()
        server.expect(ExpectedCount.once(), requestTo(EVENTS))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withException(SocketTimeoutException("Read timed out")))
        expectList()        // nothing was created after all

        publish(id)
            .andExpect(status().isGatewayTimeout)
            .andExpect(responseJsonPath("$.code").value("INTERVALS_TIMEOUT"))
        server.verify()
        assertThat(publications.findAll()).isEmpty()
        assertThat(drafts.findById(id).orElseThrow().status.name).isEqualTo("APPROVED")

        server.reset()
        expectList()
        server.expect(ExpectedCount.once(), requestTo(EVENTS))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(ownedEvent(EVENT_ID, RENDERED), MediaType.APPLICATION_JSON))
        expectReadback(EVENT_ID, ownedEvent(EVENT_ID, RENDERED))
        publish(id).andExpect(status().isOk).andExpect(responseJsonPath("$.intervalsOperation").value("CREATED"))
        server.verify()
    }

    @Test
    fun `a server that stores something else than what was sent is a readback mismatch, not a success`() {
        val id = approved()
        expectList()
        server.expect(ExpectedCount.once(), requestTo(EVENTS))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(ownedEvent(EVENT_ID, RENDERED), MediaType.APPLICATION_JSON))
        expectReadback(EVENT_ID, ownedEvent(EVENT_ID, "- Warm Up 10m\n- Main 15m\n- Cool Down 5m"))

        publish(id)
            .andExpect(status().isBadGateway)
            .andExpect(responseJsonPath("$.code").value("INTERVALS_READBACK_MISMATCH"))
        server.verify()
        assertThat(publications.findAll()).isEmpty()
    }

    // ---- the gates in front of the transport -----------------------------------------------------

    @Test
    fun `the injury gate blocks a non-REST publish before the first byte is sent`() {
        val id = approved()
        injuryReports.deleteAll()
        InjuryTestFixtures.seedActive(injuryReports, athleteService, clock, painOnWalking = true)

        publish(id)
            .andExpect(status().isUnprocessableEntity)
            .andExpect(responseJsonPath("$.code").value("WORKOUT_SAFETY_INJURY_ACTIVE"))
        server.verify()     // zero Intervals requests
        assertThat(publications.findAll()).isEmpty()
    }

    @Test
    fun `an unapproved draft never reaches the transport`() {
        val id = saved()
        preview(id).andExpect(status().isConflict)
        publish(id).andExpect(status().isConflict)
        server.verify()
    }

    @Test
    fun `an Intervals rejection carries the status code and never the credential`() {
        val id = approved()
        server.expect(ExpectedCount.once(), requestTo("$EVENTS?oldest=$DATE&newest=$DATE&category=WORKOUT"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("""{"error":"bad key wire-test-key-not-real"}"""))

        val body = publish(id)
            .andExpect(status().isUnauthorized)
            .andExpect(responseJsonPath("$.code").value("INTERVALS_AUTH_FAILED"))
            .andReturn().response.contentAsString

        assertThat(body).doesNotContain(API_KEY).doesNotContain(BASIC).doesNotContain("Basic ")
        server.verify()
        assertThat(publications.findAll()).isEmpty()
    }

    private companion object {
        const val BASE_URL = "https://intervals.test"
        const val API_KEY = "wire-test-key-not-real"
        const val EVENTS = "$BASE_URL/api/v1/athlete/0/events"
        const val EVENT_ID = "evt-wire-1"
        val DATE: LocalDate = LocalDate.of(2026, 11, 20)
        val MARKER = "runningai:workout:v1:0:$DATE"
        val BASIC = "Basic " + Base64.getEncoder()
            .encodeToString(("API_KEY:$API_KEY").toByteArray(StandardCharsets.UTF_8))

        /** Exactly the shape proposed for the first real publish: short, easy, one %LTHR target. */
        const val RENDERED = "- Warm Up 10m\n- Main 15m 70-80% LTHR hr=1s\n- Cool Down 5m"

        fun recoveryDraft(): WorkoutDraft = draft(
            date = DATE,
            title = "Recovery path validation run",
            workoutType = "EASY",
            segments = listOf(
                segment(SegmentType.WARM_UP, 10, IntensityClass.VERY_EASY),
                segment(
                    SegmentType.MAIN, 15, IntensityClass.EASY,
                    primaryTargetType = PrimaryTargetType.HEART_RATE,
                    hrPercentLthrMin = 70, hrPercentLthrMax = 80,
                ),
                segment(SegmentType.COOL_DOWN, 5, IntensityClass.VERY_EASY),
            ),
        )
    }
}
