package com.runningai.integration.garmin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.runningai.activity.ActivityRawRepository
import com.runningai.activity.ExternalSource
import com.runningai.activity.detail.ActivityDetailStore
import com.runningai.activity.detail.ActivityRawPayloadStore
import com.runningai.activity.detail.DetailCollectionOutcome
import com.runningai.activity.detail.DetailPartStatus
import com.runningai.activity.detail.DetailPayloadType
import com.runningai.activity.detail.ZoneType
import com.runningai.common.exception.ResourceNotFoundException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Detail collection end to end over the real schema (H2 + Flyway), real mappers and real stores. Only
 * the connector is replaced, by a scripted fake: no test here can reach a connector or Garmin.
 * Not `@Transactional`: raw-first commits and replace semantics must be observed as committed.
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
class GarminActivityDetailIngestionTest {

    /** Scripted connector: per part a payload or an exception; records every request. */
    class FakeDetailSource : GarminActivityDetailSource {
        val responses = mutableMapOf<GarminActivityPart, () -> JsonNode>()
        val calls = mutableListOf<GarminActivityPart>()

        override fun fetch(part: GarminActivityPart, garminActivityId: String): JsonNode {
            calls += part
            return (responses[part] ?: error("no scripted response for $part")).invoke()
        }
    }

    @TestConfiguration
    class FakeSourceConfig {
        @Bean
        @Primary
        fun fakeDetailSource() = FakeDetailSource()
    }

    @Autowired private lateinit var source: FakeDetailSource
    @Autowired private lateinit var service: GarminActivityDetailIngestionService
    @Autowired private lateinit var listIngestion: GarminActivityIngestionService
    @Autowired private lateinit var store: ActivityDetailStore
    @Autowired private lateinit var rawPayloads: ActivityRawPayloadStore
    @Autowired private lateinit var activityRaws: ActivityRawRepository
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var mockMvc: MockMvc

    private val json = ObjectMapper()
    private val garminId = "188081596"     // the synthetic id of fixtures/garmin/running-activity.json
    private var activityId: Long = 0

    private fun fixture(name: String): JsonNode =
        json.readTree(javaClass.getResourceAsStream("/fixtures/garmin/$name") ?: error("missing fixture $name"))

    private fun scriptAllParts() {
        source.calls.clear()
        source.responses.clear()
        source.responses[GarminActivityPart.DETAIL] = { json.readTree("""{"activityId":188081596,"summaryDTO":{"distance":10000.0}}""") }
        source.responses[GarminActivityPart.SPLITS] = { fixture("detail/splits.LIVE_SHAPE.ANONYMISED.json") }
        source.responses[GarminActivityPart.HR_ZONES] = { fixture("detail/hr-zones.LIVE_SHAPE.ANONYMISED.json") }
        // live: an activity recorded without a power meter answers power zones with an empty array
        source.responses[GarminActivityPart.POWER_ZONES] = { json.readTree("[]") }
        source.responses[GarminActivityPart.SAMPLES] = { fixture("detail/samples-outdoor.LIVE_SHAPE.ANONYMISED.json") }
    }

    private fun failWith(part: GarminActivityPart, reason: GarminConnectorException.Reason) {
        source.responses[part] = { throw GarminConnectorException(reason, 0, "scripted $reason") }
    }

    private fun counts() = listOf("activity_raw_payload", "activity_detail", "activity_lap", "activity_zone", "activity_sample", "activity_detail_collection")
        .associateWith { jdbc.queryForObject("select count(*) from $it", Int::class.java) }

    private fun statusOf(type: DetailPayloadType) = store.collection(activityId).single { it.payloadType == type }

    @BeforeEach
    fun setUp() {
        activityId = listIngestion.ingest(fixture("running-activity.json")).activityId()
        scriptAllParts()
    }

    @AfterEach
    fun cleanUp() {
        listOf("activity_detail_collection", "activity_sample", "activity_zone", "activity_lap", "activity_detail",
            "activity_raw_payload", "activity_raw", "activity").forEach { jdbc.update("delete from $it") }
    }

    @Test
    fun `a full collection stores every raw payload, normalises every part and reports COMPLETE`() {
        val result = service.collect(garminId)

        assertThat(result.outcome).isEqualTo(DetailCollectionOutcome.COMPLETE)
        assertThat(result.parts.associate { it.payloadType to it.status }).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                DetailPayloadType.ACTIVITY_LIST to DetailPartStatus.NORMALIZED,
                DetailPayloadType.ACTIVITY_DETAIL to DetailPartStatus.RAW_STORED,
                DetailPayloadType.SPLITS to DetailPartStatus.NORMALIZED,
                DetailPayloadType.HR_ZONES to DetailPartStatus.NORMALIZED,
                DetailPayloadType.POWER_ZONES to DetailPartStatus.EMPTY,
                DetailPayloadType.ACTIVITY_DETAILS_STREAM to DetailPartStatus.NORMALIZED,
            ),
        )
        assertThat(source.calls).containsExactly(*GarminActivityPart.entries.toTypedArray())  // once each, no retry
        assertThat(rawPayloads.storedTypes(ExternalSource.GARMIN, garminId)).containsExactlyInAnyOrder(
            DetailPayloadType.ACTIVITY_DETAIL, DetailPayloadType.SPLITS, DetailPayloadType.HR_ZONES,
            DetailPayloadType.POWER_ZONES, DetailPayloadType.ACTIVITY_DETAILS_STREAM,
        )
        assertThat(store.detail(activityId)!!.distanceMeters).isEqualTo(10000.0)
        assertThat(store.laps(activityId).map { it.lapIndex }).containsExactly(1, 2, 3, 4)
        assertThat(store.zones(activityId, ZoneType.HEART_RATE)).hasSize(5)
        assertThat(store.zones(activityId, ZoneType.POWER)).isEmpty()
        assertThat(store.samples(activityId).map { it.heartRate }).containsExactly(98.0, 104.0, 111.0)
        // the existing list raw is untouched: still exactly one activity_raw row with the list payload
        assertThat(activityRaws.count()).isEqualTo(1)
        assertThat(activityRaws.findAll().single().payload.get("activityId").asLong()).isEqualTo(188081596L)
    }

    @Test
    fun `collecting the same activity again replaces rows instead of duplicating them`() {
        service.collect(garminId)
        val first = counts()

        scriptAllParts()
        service.collect(garminId)

        assertThat(counts()).isEqualTo(first)
        assertThat(first).containsEntry("activity_raw_payload", 5).containsEntry("activity_detail", 1)
            .containsEntry("activity_lap", 4).containsEntry("activity_zone", 5).containsEntry("activity_sample", 3)
            .containsEntry("activity_detail_collection", 6)
    }

    @Test
    fun `fewer laps on a later collection leave no stale lap behind`() {
        service.collect(garminId)
        scriptAllParts()
        source.responses[GarminActivityPart.SPLITS] = { json.readTree("""{"lapDTOs":[{"lapIndex":1,"duration":42.0}]}""") }

        service.collect(garminId)

        assertThat(store.laps(activityId).map { it.lapIndex to it.durationSeconds }).containsExactly(1 to 42.0)
    }

    @Test
    fun `the raw payload is committed before the mapper, and a mapping failure keeps the previous rows`() {
        service.collect(garminId)
        scriptAllParts()
        val ambiguous = """{"metricDescriptors":[{"metricsIndex":0,"key":"directHeartRate"},{"metricsIndex":0,"key":"directSpeed"}],
                           "activityDetailMetrics":[{"metrics":[1.0]}]}"""
        source.responses[GarminActivityPart.SAMPLES] = { json.readTree(ambiguous) }

        val result = service.collect(garminId)

        assertThat(result.outcome).isEqualTo(DetailCollectionOutcome.PARTIAL)
        assertThat(statusOf(DetailPayloadType.ACTIVITY_DETAILS_STREAM).status).isEqualTo(DetailPartStatus.MAPPING_FAILED)
        assertThat(statusOf(DetailPayloadType.ACTIVITY_DETAILS_STREAM).errorCode).isEqualTo("AMBIGUOUS_METRIC_DESCRIPTOR")
        // the new raw is stored (so it can be reprocessed), the previously normalised samples are untouched
        assertThat(rawPayloads.find(ExternalSource.GARMIN, garminId, DetailPayloadType.ACTIVITY_DETAILS_STREAM))
            .isEqualTo(json.readTree(ambiguous))
        assertThat(store.samples(activityId)).hasSize(3)
    }

    @Test
    fun `a per-part failure is recorded, the other parts continue and the run is PARTIAL`() {
        failWith(GarminActivityPart.SPLITS, GarminConnectorException.Reason.NOT_FOUND)

        val result = service.collect(garminId)

        assertThat(result.outcome).isEqualTo(DetailCollectionOutcome.PARTIAL)
        assertThat(statusOf(DetailPayloadType.SPLITS).status).isEqualTo(DetailPartStatus.FETCH_FAILED)
        assertThat(statusOf(DetailPayloadType.SPLITS).errorCode).isEqualTo("NOT_FOUND")
        assertThat(source.calls).hasSize(5)
        assertThat(rawPayloads.find(ExternalSource.GARMIN, garminId, DetailPayloadType.SPLITS)).isNull()
        assertThat(store.samples(activityId)).hasSize(3)
    }

    @Test
    fun `a rate limit stops the run at once, is recorded and propagates`() {
        failWith(GarminActivityPart.SPLITS, GarminConnectorException.Reason.RATE_LIMITED)

        assertThatThrownBy { service.collect(garminId) }
            .isInstanceOfSatisfying(GarminConnectorException::class.java) {
                assertThat(it.reason).isEqualTo(GarminConnectorException.Reason.RATE_LIMITED)
            }

        assertThat(source.calls).containsExactly(GarminActivityPart.DETAIL, GarminActivityPart.SPLITS)
        assertThat(statusOf(DetailPayloadType.SPLITS).status).isEqualTo(DetailPartStatus.FETCH_FAILED)
        assertThat(statusOf(DetailPayloadType.ACTIVITY_DETAIL).status).isEqualTo(DetailPartStatus.RAW_STORED)
        assertThat(store.collection(activityId).map { it.payloadType })
            .doesNotContain(DetailPayloadType.HR_ZONES, DetailPayloadType.ACTIVITY_DETAILS_STREAM)
    }

    @Test
    fun `an auth failure on the first part fails the run without calling Garmin again`() {
        failWith(GarminActivityPart.DETAIL, GarminConnectorException.Reason.AUTH_REQUIRED)

        assertThatThrownBy { service.collect(garminId) }.isInstanceOf(GarminConnectorException::class.java)

        assertThat(source.calls).containsExactly(GarminActivityPart.DETAIL)
    }

    @Test
    fun `every remote part failing is still PARTIAL because the list part succeeded, never COMPLETE`() {
        GarminActivityPart.entries.forEach { failWith(it, GarminConnectorException.Reason.UPSTREAM_ERROR) }

        val result = service.collect(garminId)

        assertThat(result.outcome).isEqualTo(DetailCollectionOutcome.PARTIAL)
        assertThat(result.parts.filter { it.status == DetailPartStatus.FETCH_FAILED }).hasSize(5)
    }

    @Test
    fun `nothing succeeding is FAILED`() {
        GarminActivityPart.entries.forEach { failWith(it, GarminConnectorException.Reason.UPSTREAM_ERROR) }
        jdbc.update("delete from activity_raw")      // the list item is gone too

        val result = service.collect(garminId)

        assertThat(result.outcome).isEqualTo(DetailCollectionOutcome.FAILED)
        assertThat(statusOf(DetailPayloadType.ACTIVITY_LIST).errorCode).isEqualTo("RAW_MISSING")
    }

    @Test
    fun `an activity that was never ingested is refused before any connector call`() {
        assertThatThrownBy { service.collect("999999999") }.isInstanceOf(ResourceNotFoundException::class.java)
        assertThat(source.calls).isEmpty()
    }

    @Test
    fun `reprocess re-normalises the stored raw payloads without contacting Garmin`() {
        service.collect(garminId)
        jdbc.update("delete from activity_sample")
        jdbc.update("delete from activity_lap")
        source.calls.clear()

        val result = service.reprocess(garminId)

        assertThat(source.calls).isEmpty()
        assertThat(result.outcome).isEqualTo(DetailCollectionOutcome.COMPLETE)
        assertThat(store.samples(activityId)).hasSize(3)
        assertThat(store.laps(activityId)).hasSize(4)
    }

    // ---- HTTP -------------------------------------------------------------------------------------------

    @Test
    fun `the manual endpoint returns the per-part result`() {
        mockMvc.perform(post("/api/v1/garmin/activities/$garminId/details"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.outcome").value("COMPLETE"))
            .andExpect(jsonPath("$.activityId").value(activityId))
            .andExpect(jsonPath("$.parts.length()").value(6))
    }

    @Test
    fun `connector failures keep the Garmin sync error contract`() {
        failWith(GarminActivityPart.DETAIL, GarminConnectorException.Reason.RATE_LIMITED)

        mockMvc.perform(post("/api/v1/garmin/activities/$garminId/details"))
            .andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.code").value("GARMIN_RATE_LIMITED"))
    }

    @Test
    fun `invalid or unknown activity ids are refused`() {
        mockMvc.perform(post("/api/v1/garmin/activities/abc/details")).andExpect(status().isNotFound)
        mockMvc.perform(post("/api/v1/garmin/activities/0/details")).andExpect(status().isNotFound)
        mockMvc.perform(post("/api/v1/garmin/activities/777/details"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("ACTIVITY_NOT_FOUND"))
        assertThat(source.calls).isEmpty()
    }
}
