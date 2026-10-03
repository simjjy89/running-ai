package com.runningai.integration.intervals

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.runningai.activity.Activity
import com.runningai.activity.ActivityRepository
import com.runningai.activity.ActivityType
import com.runningai.activity.ExternalSource
import com.runningai.athlete.AthleteService
import com.runningai.common.exception.ResourceNotFoundException
import com.runningai.enrichment.IntervalsEnrichmentStore
import com.runningai.enrichment.IntervalsPayloadType
import com.runningai.enrichment.MatchMethod
import com.runningai.enrichment.SourceLinkConflictException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.time.LocalDate

/**
 * Enrichment end to end over the real schema (H2 + Flyway V17/V18), real mappers, matcher and store.
 * Only the read client is replaced by a scripted fake: no test here can reach Intervals.icu, and the
 * fake also counts calls, which is how reprocess proves its zero-network claim. Not `@Transactional`:
 * raw-first commits and upsert semantics must be observed as committed.
 */
@SpringBootTest(
    properties = [
        "running-ai.garmin.connector.base-url=http://127.0.0.1:9",
        "running-ai.intervals.api-key=",
        "running-ai.intervals.base-url=http://127.0.0.1:9",
    ],
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IntervalsEnrichmentServiceTest {

    /** Scripted read-only client; by construction it has no write method to misuse. */
    class FakeIntervalsReadClient : IntervalsReadClient {
        var activitiesResponse: () -> JsonNode = { error("no scripted activities response") }
        var wellnessResponse: () -> JsonNode = { error("no scripted wellness response") }
        var calls = 0
        var lastOldest: LocalDate? = null
        var lastNewest: LocalDate? = null

        override fun listActivities(oldest: LocalDate, newest: LocalDate): IntervalsReadResponse {
            calls++
            lastOldest = oldest
            lastNewest = newest
            return IntervalsReadResponse(activitiesResponse(), IntervalsRateLimit.UNKNOWN)
        }

        override fun getActivity(intervalsActivityId: String): IntervalsReadResponse =
            getActivity(intervalsActivityId, false)

        override fun getActivity(intervalsActivityId: String, includeIntervals: Boolean): IntervalsReadResponse {
            calls++
            error("not scripted in these tests")
        }

        override fun getWellness(oldest: LocalDate, newest: LocalDate): IntervalsReadResponse {
            calls++
            lastOldest = oldest
            lastNewest = newest
            return IntervalsReadResponse(wellnessResponse(), IntervalsRateLimit.UNKNOWN)
        }
    }

    @TestConfiguration
    class FakeClientConfig {
        @Bean
        @Primary
        fun fakeIntervalsReadClient() = FakeIntervalsReadClient()
    }

    @Autowired private lateinit var client: FakeIntervalsReadClient
    @Autowired private lateinit var service: IntervalsEnrichmentService
    @Autowired private lateinit var store: IntervalsEnrichmentStore
    @Autowired private lateinit var activities: ActivityRepository
    @Autowired private lateinit var athleteService: AthleteService
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var mockMvc: MockMvc

    private val json = ObjectMapper()
    private var athleteId: Long = 0

    private fun fixture(name: String): JsonNode =
        json.readTree(javaClass.getResourceAsStream("/fixtures/intervals/$name"))

    @BeforeEach
    fun setUp() {
        athleteId = athleteService.defaultAthlete.id
        client.activitiesResponse = { fixture("activities-list.LIVE_SHAPE.ANONYMISED.json") }
        client.wellnessResponse = { fixture("wellness.LIVE_SHAPE.ANONYMISED.json") }
        client.calls = 0
    }

    @AfterEach
    fun cleanUp() {
        jdbc.update("DELETE FROM activity_intervals_metrics")
        jdbc.update("DELETE FROM activity_source_link")
        jdbc.update("DELETE FROM intervals_raw_payload")
        jdbc.update("DELETE FROM intervals_fitness_daily")
        jdbc.update("DELETE FROM activity WHERE external_id LIKE '9000000000%'")
    }

    private fun garminRun(externalId: String = "90000000001"): Activity = activities.save(Activity(
        athleteId, ExternalSource.GARMIN, externalId, ActivityType.RUN,
        Instant.parse("2026-01-09T23:00:10Z"), 2783, 9917.21, 185, 192,
    ))

    @Test
    fun `enrichment is raw-first, links by SOURCE_ID and normalises the live-verified metrics`() {
        val activity = garminRun()
        val result = service.enrichActivity(activity.id)

        assertThat(result.matchStatus).isEqualTo(MatchStatus.MATCHED)
        assertThat(result.matchMethod).isEqualTo(MatchMethod.SOURCE_ID)
        assertThat(result.intervalsActivityId).isEqualTo("i00000001")
        assertThat(result.evidence!!.startTimeDeltaSeconds).isEqualTo(0)
        assertThat(result.evidence!!.durationDeltaSeconds).isEqualTo(0)
        assertThat(result.evidence!!.distanceDeltaMeters).isCloseTo(0.0, within(1e-9))
        assertThat(result.metrics!!.trainingLoad).isEqualTo(89)
        assertThat(result.metrics!!.intensity).isCloseTo(107.316795, within(1e-6))
        assertThat(result.metrics!!.ctlAfterActivity).isCloseTo(14.982259, within(1e-6))
        assertThat(result.metrics!!.atlAfterActivity).isCloseTo(26.2602, within(1e-6))

        // One GET, over the athlete-local +/-1 day window (Asia/Seoul: 2026-01-10 local)
        assertThat(client.calls).isEqualTo(1)
        assertThat(client.lastOldest).isEqualTo(LocalDate.parse("2026-01-09"))
        assertThat(client.lastNewest).isEqualTo(LocalDate.parse("2026-01-11"))

        // Raw stored before normalisation, link row written
        assertThat(store.findRaw(athleteId, IntervalsPayloadType.ACTIVITY, "i00000001")).isNotNull
        val link = store.findLink(activity.id, ExternalSource.INTERVALS_ICU)!!
        assertThat(link.externalActivityId).isEqualTo("i00000001")
        assertThat(link.matchMethod).isEqualTo(MatchMethod.SOURCE_ID)
    }

    @Test
    fun `a second enrichment of the same content is idempotent - no duplicate rows, same result`() {
        val activity = garminRun()
        val first = service.enrichActivity(activity.id)
        val second = service.enrichActivity(activity.id)

        assertThat(second.metrics).usingRecursiveComparison().ignoringFields("fetchedAt")
            .isEqualTo(first.metrics)
        assertThat(count("intervals_raw_payload")).isEqualTo(1)
        assertThat(count("activity_source_link")).isEqualTo(1)
        assertThat(count("activity_intervals_metrics")).isEqualTo(1)
    }

    @Test
    fun `no Intervals activity for this Garmin id and no composite candidate is UNMATCHED, nothing stored`() {
        val activity = garminRun(externalId = "90000000099")
        val result = service.enrichActivity(activity.id)

        assertThat(result.matchStatus).isEqualTo(MatchStatus.UNMATCHED)
        assertThat(count("intervals_raw_payload")).isEqualTo(0)
        assertThat(count("activity_source_link")).isEqualTo(0)
        assertThat(count("activity_intervals_metrics")).isEqualTo(0)
    }

    @Test
    fun `two Intervals activities claiming the same Garmin id are AMBIGUOUS, nothing stored`() {
        val duplicated = fixture("activities-list.LIVE_SHAPE.ANONYMISED.json") as ArrayNode
        val copy = (duplicated[0] as ObjectNode).deepCopy().put("id", "i00000001-dup")
        duplicated.add(copy)
        client.activitiesResponse = { duplicated }

        val activity = garminRun()
        val result = service.enrichActivity(activity.id)

        assertThat(result.matchStatus).isEqualTo(MatchStatus.AMBIGUOUS)
        assertThat(result.candidateCount).isEqualTo(2)
        assertThat(count("activity_source_link")).isEqualTo(0)
    }

    @Test
    fun `an Intervals activity already linked elsewhere is a conflict, never re-linked automatically`() {
        val first = garminRun()
        service.enrichActivity(first.id)

        // A different RunningAI activity whose Garmin id the same Intervals activity suddenly claims
        val second = garminRun(externalId = "90000000098")
        val hijacked = fixture("activities-list.LIVE_SHAPE.ANONYMISED.json") as ArrayNode
        (hijacked[0] as ObjectNode).put("external_id", "90000000098")
        client.activitiesResponse = { hijacked }

        assertThatThrownBy { service.enrichActivity(second.id) }
            .isInstanceOf(SourceLinkConflictException::class.java)
        assertThat(store.findLink(first.id, ExternalSource.INTERVALS_ICU)!!.externalActivityId).isEqualTo("i00000001")
        assertThat(store.findLink(second.id, ExternalSource.INTERVALS_ICU)).isNull()
    }

    @Test
    fun `reprocess rebuilds metrics from the stored raw payload with zero Intervals calls`() {
        val activity = garminRun()
        val enriched = service.enrichActivity(activity.id)
        client.calls = 0

        val reprocessed = service.reprocessActivity(activity.id)

        assertThat(client.calls).isEqualTo(0)
        assertThat(reprocessed.reprocessed).isTrue()
        // fetchedAt round-trips through H2 at microsecond precision; values are compared exactly
        assertThat(reprocessed.metrics).usingRecursiveComparison().ignoringFields("fetchedAt")
            .isEqualTo(enriched.metrics)
        assertThat(reprocessed.metrics!!.fetchedAt).isCloseTo(enriched.metrics!!.fetchedAt,
            within(1, java.time.temporal.ChronoUnit.MILLIS))
        assertThat(count("intervals_raw_payload")).isEqualTo(1)
        assertThat(count("activity_intervals_metrics")).isEqualTo(1)
    }

    @Test
    fun `reprocess without a prior enrichment is a 404-style failure, not a fetch`() {
        val activity = garminRun()
        assertThatThrownBy { service.reprocessActivity(activity.id) }
            .isInstanceOf(ResourceNotFoundException::class.java)
        assertThat(client.calls).isEqualTo(0)
    }

    @Test
    fun `fitness enrichment stores ctl and atl per day and derives form only from both`() {
        val result = service.enrichFitness(LocalDate.parse("2026-01-10"), LocalDate.parse("2026-01-12"))

        assertThat(result.daysFetched).isEqualTo(3)
        assertThat(result.daysStored).isEqualTo(3)
        assertThat(result.failedDays).isEqualTo(0)

        val days = service.fitnessDays(LocalDate.parse("2026-01-10"), LocalDate.parse("2026-01-12"))
        assertThat(days).hasSize(3)
        val full = days[0]
        assertThat(full.ctl).isCloseTo(14.982259, within(1e-6))
        assertThat(full.atl).isCloseTo(26.2602, within(1e-6))
        assertThat(full.derivedForm).isCloseTo(14.982259 - 26.2602, within(1e-6))
        assertThat(full.ctlLoad).isEqualTo(89.0)
        assertThat(full.rampRate).isCloseTo(0.37189484, within(1e-6))

        val partial = days[1]
        assertThat(partial.ctl).isNotNull()
        assertThat(partial.atl).isNull()
        assertThat(partial.derivedForm).isNull()

        // 2026-01-12 carries subjective fatigue = 4; ATL must be the calculated 12.9, never 4
        val subjective = days[2]
        assertThat(subjective.atl).isCloseTo(12.9, within(1e-9))
    }

    @Test
    fun `fitness enrichment is idempotent per day`() {
        service.enrichFitness(LocalDate.parse("2026-01-10"), LocalDate.parse("2026-01-12"))
        service.enrichFitness(LocalDate.parse("2026-01-10"), LocalDate.parse("2026-01-12"))

        assertThat(count("intervals_fitness_daily")).isEqualTo(3)
        assertThat(count("intervals_raw_payload")).isEqualTo(3)
    }

    @Test
    fun `fitness reprocess re-normalises stored days with zero Intervals calls`() {
        service.enrichFitness(LocalDate.parse("2026-01-10"), LocalDate.parse("2026-01-12"))
        client.calls = 0

        val result = service.reprocessFitness(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-31"))

        assertThat(client.calls).isEqualTo(0)
        assertThat(result.daysFetched).isEqualTo(3)
        assertThat(result.daysStored).isEqualTo(3)
        assertThat(result.reprocessed).isTrue()
    }

    @Test
    fun `the fitness window is capped - backfill is not this endpoint`() {
        assertThatThrownBy { service.enrichFitness(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-03-01")) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(client.calls).isEqualTo(0)
    }

    // ---- HTTP API ----

    @Test
    fun `manual enrichment and stored-result query over HTTP`() {
        val activity = garminRun()

        mockMvc.perform(post("/api/v1/intervals/enrichment/activities/{id}", activity.id))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.matchStatus").value("MATCHED"))
            .andExpect(jsonPath("$.matchMethod").value("SOURCE_ID"))
            .andExpect(jsonPath("$.metrics.trainingLoad").value(89))

        mockMvc.perform(get("/api/v1/activities/{id}/intervals", activity.id))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.link.externalActivityId").value("i00000001"))
            .andExpect(jsonPath("$.metrics.ctlAfterActivity").value(14.982259))

        mockMvc.perform(get("/api/v1/intervals/fitness").param("oldest", "2026-01-10").param("newest", "2026-01-12"))
            .andExpect(status().isOk)
    }

    @Test
    fun `an unknown activity is 404 and an unenriched one has no stored view`() {
        mockMvc.perform(post("/api/v1/intervals/enrichment/activities/999999"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("ACTIVITY_NOT_FOUND"))

        val activity = garminRun()
        mockMvc.perform(get("/api/v1/activities/{id}/intervals", activity.id))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("INTERVALS_ENRICHMENT_NOT_FOUND"))
    }

    @Test
    fun `missing key, auth failure and rate limit map to 503, 401 and 429 - and nothing retries`() {
        val activity = garminRun()
        var attempts = 0
        client.activitiesResponse = {
            attempts++
            throw IntervalsException(IntervalsException.Reason.NOT_CONFIGURED, null, "no key")
        }
        mockMvc.perform(post("/api/v1/intervals/enrichment/activities/{id}", activity.id))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("INTERVALS_NOT_CONFIGURED"))
        assertThat(attempts).isEqualTo(1)

        attempts = 0
        client.activitiesResponse = {
            attempts++
            throw IntervalsException(IntervalsException.Reason.AUTH_FAILED, 401, "401")
        }
        mockMvc.perform(post("/api/v1/intervals/enrichment/activities/{id}", activity.id))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("INTERVALS_AUTH_FAILED"))
        assertThat(attempts).isEqualTo(1)

        attempts = 0
        client.activitiesResponse = {
            attempts++
            throw IntervalsException(IntervalsException.Reason.RATE_LIMITED, 429, "429")
        }
        mockMvc.perform(post("/api/v1/intervals/enrichment/activities/{id}", activity.id))
            .andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.code").value("INTERVALS_RATE_LIMITED"))
        assertThat(attempts).isEqualTo(1)

        // A failed fetch stored nothing
        assertThat(count("intervals_raw_payload")).isEqualTo(0)
        assertThat(count("activity_source_link")).isEqualTo(0)
    }

    @Test
    fun `an invalid fitness range over HTTP is a 400`() {
        mockMvc.perform(post("/api/v1/intervals/enrichment/fitness")
            .param("oldest", "2026-01-01").param("newest", "2026-03-01"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_ENRICHMENT_REQUEST"))
    }

    private fun count(table: String): Int =
        jdbc.queryForObject("SELECT COUNT(*) FROM $table", Int::class.java)!!
}
