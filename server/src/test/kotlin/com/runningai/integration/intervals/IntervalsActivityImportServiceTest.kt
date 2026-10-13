package com.runningai.integration.intervals

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.runningai.activity.Activity
import com.runningai.activity.ActivityRepository
import com.runningai.activity.ActivityType
import com.runningai.activity.ExternalSource
import com.runningai.activity.detail.ActivityDetailStore
import com.runningai.activity.detail.DetailPartStatus
import com.runningai.activity.detail.DetailPayloadType
import com.runningai.activity.detail.SampleCompleteness
import com.runningai.analysis.RunningActivityAnalysisService
import com.runningai.athlete.AthleteService
import com.runningai.enrichment.IntervalsEnrichmentStore
import com.runningai.enrichment.IntervalsImportOutcome
import com.runningai.enrichment.IntervalsPayloadType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.time.LocalDate

/**
 * The Intervals-only importer end to end over the real schema (H2 + Flyway V25), the real mappers,
 * matcher, detail store, analysis engine and enrichment store. The only fake is the Intervals.icu
 * transport, which also counts its calls - that is how the "a second run fetches nothing and adds
 * nothing" claim is proven rather than asserted.
 *
 * Not `@Transactional`: the importer's per-activity records have to be observed as committed, which is
 * what makes a re-invocation a resume.
 */
@SpringBootTest(
    properties = [
        "running-ai.garmin.connector.base-url=http://127.0.0.1:9",
        "running-ai.intervals.api-key=",
        "running-ai.intervals.base-url=http://127.0.0.1:9",
        "running-ai.intervals.activity-import.enabled=true",
        "running-ai.intervals.activity-import.activity-delay=0s",
    ],
)
@ActiveProfiles("test")
class IntervalsActivityImportServiceTest {

    class FakeReadClient : IntervalsReadClient {
        private val json = ObjectMapper()
        var activities: List<JsonNode> = emptyList()
        var streams: MutableMap<String, JsonNode> = mutableMapOf()
        var failStreamsWith: IntervalsException? = null
        val listCalls = mutableListOf<Pair<LocalDate, LocalDate>>()
        val streamCalls = mutableListOf<String>()

        override fun listActivities(oldest: LocalDate, newest: LocalDate): IntervalsReadResponse {
            listCalls += oldest to newest
            return response(json.createArrayNode().addAll(activities))
        }

        override fun getActivity(intervalsActivityId: String) = getActivity(intervalsActivityId, false)

        override fun getActivity(intervalsActivityId: String, includeIntervals: Boolean) =
            response(json.createObjectNode())

        override fun getActivityStreams(intervalsActivityId: String): IntervalsReadResponse {
            streamCalls += intervalsActivityId
            failStreamsWith?.let { throw it }
            return response(streams[intervalsActivityId] ?: json.createArrayNode())
        }

        override fun getWellness(oldest: LocalDate, newest: LocalDate) = response(json.createArrayNode())

        private fun response(body: JsonNode) = IntervalsReadResponse(body, IntervalsRateLimit(null, null, null))
    }

    @TestConfiguration
    class Fakes {
        @Bean @Primary fun fakeReadClient() = FakeReadClient()
    }

    @Autowired private lateinit var service: IntervalsActivityImportService
    @Autowired private lateinit var readClient: FakeReadClient
    @Autowired private lateinit var activities: ActivityRepository
    @Autowired private lateinit var detail: ActivityDetailStore
    @Autowired private lateinit var enrichment: IntervalsEnrichmentStore
    @Autowired private lateinit var analysis: RunningActivityAnalysisService
    @Autowired private lateinit var athletes: AthleteService
    @Autowired private lateinit var jdbc: JdbcTemplate

    private val json = ObjectMapper()
    private val window = IntervalsActivityImportRequest(LocalDate.parse("2026-08-01"), LocalDate.parse("2026-09-30"))

    @BeforeEach
    fun setUp() {
        readClient.activities = emptyList()
        readClient.streams = mutableMapOf()
        readClient.failStreamsWith = null
        readClient.listCalls.clear()
        readClient.streamCalls.clear()
    }

    @AfterEach
    fun cleanUp() {
        listOf(
            "activity_analysis_interval", "activity_analysis_interval_group", "activity_analysis",
            "intervals_activity_import", "activity_detail_collection", "activity_sample", "activity_zone",
            "activity_lap", "activity_detail", "activity_raw_payload", "activity_raw",
            "activity_intervals_metrics", "activity_source_link", "activity", "intervals_raw_payload",
        ).forEach { jdbc.update("delete from $it") }
    }

    // ---- fixtures, shaped like the live payloads ----

    private fun oauthActivity(
        id: String = "i180579445",
        type: String = "Run",
        startLocal: String = "2026-08-02T09:30:01",
        startUtc: String = "2026-08-02T00:30:01Z",
        elapsed: Int = 3234,
        moving: Int = 3233,
        recording: Int = 3233,
        distance: Double = 9588.69,
        analyzed: String = "2026-08-28T06:29:14.144+00:00",
    ): ObjectNode = json.createObjectNode().apply {
        put("id", id)
        put("source", "OAUTH_CLIENT")
        put("external_id", "29CDB71A-E1B1-432B-93C7-9C59F1A77E7D")
        put("strava_id", "19929243399")
        put("device_name", "Intervals Companion")
        put("type", type)
        put("start_date_local", startLocal)
        put("start_date", startUtc)
        put("elapsed_time", elapsed)
        put("moving_time", moving)
        put("icu_recording_time", recording)
        put("distance", distance)
        put("average_heartrate", 146)
        put("max_heartrate", 168)
        put("average_speed", 2.966)
        put("icu_lap_count", 0)
        put("icu_training_load", 61)
        put("icu_intensity", 0.78)
        put("icu_ctl", 12.5)
        put("icu_atl", 18.25)
        put("analyzed", analyzed)
    }

    private fun garminSourcedActivity(garminId: String) = json.createObjectNode().apply {
        put("id", "i187812124")
        put("source", "GARMIN_CONNECT")
        put("external_id", garminId)
        put("type", "Run")
        put("start_date_local", "2026-09-19T08:00:00")
        put("start_date", "2026-09-18T23:00:00Z")
        put("elapsed_time", 3600)
        put("moving_time", 3600)
        put("icu_recording_time", 3600)
        put("distance", 9920.0)
        put("icu_lap_count", 12)
        put("icu_training_load", 89)
        put("analyzed", "2026-09-19T09:00:00+00:00")
    }

    private fun stravaStub(id: String = "i170000001") = json.createObjectNode().apply {
        put("id", id)
        put("icu_athlete_id", "i692548")
        put("start_date_local", "2026-07-02T07:12:00")
        put("source", "STRAVA")
        put("_note", "STRAVA activities are not available via the API")
    }

    /** `count` points at 1 Hz, the live stream shape. */
    private fun streamPayload(count: Int): JsonNode {
        fun stream(type: String, value: (Int) -> Number?): ObjectNode = json.createObjectNode().apply {
            put("type", type)
            val data = putArray("data")
            (0 until count).forEach { i ->
                val v = value(i)
                when (v) {
                    null -> data.addNull()
                    is Int -> data.add(v)
                    else -> data.add(v.toDouble())
                }
            }
            put("allNull", false)
        }
        return json.createArrayNode()
            .add(stream("time") { it })
            .add(stream("heartrate") { 140 + (it % 20) })
            .add(stream("distance") { it * 2.9 })
            .add(stream("velocity_smooth") { 2.9 })
            .add(stream("cadence") { 86 })
            .add(stream("stance_time") { 254.0 })
    }

    // ---- tests ----

    @Test
    fun `an Intervals-only activity becomes an activity with its own source identity and 1 Hz samples`() {
        readClient.activities = listOf(oauthActivity())
        readClient.streams["i180579445"] = streamPayload(600)

        val result = service.importRange(window)

        assertThat(result.imported).isEqualTo(1)
        assertThat(result.samplesWritten).isEqualTo(600)

        val activity = activities.findByExternalSourceAndExternalId(ExternalSource.INTERVALS_ICU, "i180579445").orElseThrow()
        assertThat(activity.externalSource).isEqualTo(ExternalSource.INTERVALS_ICU)
        assertThat(activity.activityType).isEqualTo(ActivityType.RUN)
        assertThat(activity.startedAt).isEqualTo(Instant.parse("2026-08-02T00:30:01Z"))
        assertThat(activity.durationSeconds).isEqualTo(3233)
        assertThat(activity.distanceMeters).isEqualTo(9588.69)

        val samples = detail.samples(activity.id)
        assertThat(samples).hasSize(600)
        assertThat(samples.first().sampleTime).isEqualTo(Instant.parse("2026-08-02T00:30:01Z"))
        assertThat(samples.first().heartRate).isEqualTo(140.0)
        // cadence is rpm at the source: kept, but not in the steps-per-minute column
        assertThat(samples.first().cadence).isNull()
        assertThat(samples.first().extraMetrics?.get("cadence")?.asInt()).isEqualTo(86)
    }

    @Test
    fun `an imported activity carries the Intervals training-model numbers like any enriched one`() {
        readClient.activities = listOf(oauthActivity())
        readClient.streams["i180579445"] = streamPayload(60)

        service.importRange(window)

        val activity = activities.findByExternalSourceAndExternalId(ExternalSource.INTERVALS_ICU, "i180579445").orElseThrow()
        val metrics = enrichment.findMetrics(activity.id)!!
        assertThat(metrics.intervalsActivityId).isEqualTo("i180579445")
        assertThat(metrics.trainingLoad).isEqualTo(61)
        assertThat(metrics.intensity).isEqualTo(0.78)
        // CTL = Intervals calculated fitness, ATL = Intervals calculated fatigue - terminology unchanged
        assertThat(metrics.ctlAfterActivity).isEqualTo(12.5)
        assertThat(metrics.atlAfterActivity).isEqualTo(18.25)
    }

    @Test
    fun `the raw payloads are stored so a reprocess needs no API call`() {
        readClient.activities = listOf(oauthActivity())
        readClient.streams["i180579445"] = streamPayload(60)
        service.importRange(window)
        val callsAfterImport = readClient.streamCalls.size

        assertThat(enrichment.findRaw(athletes.defaultAthlete.id, IntervalsPayloadType.ACTIVITY, "i180579445")).isNotNull()

        val record = service.reprocess("i180579445")

        assertThat(record.outcome).isEqualTo(IntervalsImportOutcome.REFRESHED)
        assertThat(record.sampleCount).isEqualTo(60)
        assertThat(readClient.streamCalls).hasSize(callsAfterImport)
        assertThat(readClient.listCalls).hasSize(1)
    }

    @Test
    fun `no lap is invented for a source that reports none`() {
        readClient.activities = listOf(oauthActivity())
        readClient.streams["i180579445"] = streamPayload(60)

        service.importRange(window)

        val activity = activities.findByExternalSourceAndExternalId(ExternalSource.INTERVALS_ICU, "i180579445").orElseThrow()
        assertThat(detail.laps(activity.id)).isEmpty()
        // and the emptiness is recorded rather than left to be guessed at later
        val splits = detail.part(activity.id, DetailPayloadType.SPLITS)!!
        assertThat(splits.status).isEqualTo(DetailPartStatus.EMPTY)
        assertThat(splits.itemCount).isZero()
    }

    @Test
    fun `stream fidelity is UNKNOWN because Intervals states no native point count`() {
        readClient.activities = listOf(oauthActivity())
        readClient.streams["i180579445"] = streamPayload(600)

        service.importRange(window)

        val activity = activities.findByExternalSourceAndExternalId(ExternalSource.INTERVALS_ICU, "i180579445").orElseThrow()
        val stream = detail.part(activity.id, DetailPayloadType.ACTIVITY_DETAILS_STREAM)!!
        assertThat(stream.status).isEqualTo(DetailPartStatus.NORMALIZED)
        assertThat(stream.sampleFidelity!!.completeness).isEqualTo(SampleCompleteness.UNKNOWN)
        assertThat(stream.sampleFidelity!!.sourceMetricsCount).isEqualTo(600)
        assertThat(stream.sampleFidelity!!.sourceTotalMetricsCount).isNull()
        assertThat(stream.sampleFidelity!!.requestedMaxChartSize).isNull()
    }

    @Test
    fun `the imported activity is analysed`() {
        readClient.activities = listOf(oauthActivity())
        readClient.streams["i180579445"] = streamPayload(600)

        service.importRange(window)

        val activity = activities.findByExternalSourceAndExternalId(ExternalSource.INTERVALS_ICU, "i180579445").orElseThrow()
        val stored = analysis.find(activity.id)!!
        assertThat(stored.session.validSampleCount).isEqualTo(600)
        assertThat(stored.inputDigest).isNotNull()
        // no laps and no zones were imported, so neither can produce a number
        assertThat(stored.laps.lapCount).isZero()
        assertThat(stored.intervalGroups).isEmpty()
        assertThat(stored.zones.totalSeconds).isNull()
    }

    @Test
    fun `running the same import twice adds nothing and fetches no stream the second time`() {
        readClient.activities = listOf(oauthActivity())
        readClient.streams["i180579445"] = streamPayload(600)
        service.importRange(window)
        val before = counts()

        val second = service.importRange(window)

        assertThat(second.imported).isZero()
        assertThat(second.alreadyImported).isEqualTo(1)
        assertThat(counts()).isEqualTo(before)
        assertThat(readClient.streamCalls).containsExactly("i180579445")
    }

    @Test
    fun `an activity Intervals recomputed since the last import is refreshed in place`() {
        readClient.activities = listOf(oauthActivity())
        readClient.streams["i180579445"] = streamPayload(600)
        service.importRange(window)

        readClient.activities = listOf(oauthActivity(analyzed = "2026-09-01T00:00:00+00:00"))
        readClient.streams["i180579445"] = streamPayload(700)
        val second = service.importRange(window)

        assertThat(second.refreshed).isEqualTo(1)
        assertThat(activities.count()).isEqualTo(1)
        val activity = activities.findByExternalSourceAndExternalId(ExternalSource.INTERVALS_ICU, "i180579445").orElseThrow()
        assertThat(detail.samples(activity.id)).hasSize(700)
    }

    @Test
    fun `a workout RunningAI already has is linked, not duplicated`() {
        val garmin = activities.save(
            Activity(
                athletes.defaultAthlete.id, ExternalSource.GARMIN, "24413424790", ActivityType.RUN,
                Instant.parse("2026-08-02T00:30:01Z"), 3233, 9588.69, 146, 168,
            ),
        )
        readClient.activities = listOf(oauthActivity())

        val result = service.importRange(window)

        assertThat(result.linkedExisting).isEqualTo(1)
        assertThat(result.imported).isZero()
        assertThat(activities.count()).isEqualTo(1)
        // the Intervals identity is now attached to the activity that already existed
        val link = enrichment.findLink(garmin.id, ExternalSource.INTERVALS_ICU)!!
        assertThat(link.externalActivityId).isEqualTo("i180579445")
        assertThat(enrichment.findMetrics(garmin.id)).isNotNull()
        // and no stream was fetched for an activity that was never going to be imported
        assertThat(readClient.streamCalls).isEmpty()
    }

    @Test
    fun `two candidate activities are never merged automatically`() {
        val athleteId = athletes.defaultAthlete.id
        activities.save(Activity(athleteId, ExternalSource.GARMIN, "g-1", ActivityType.RUN,
            Instant.parse("2026-08-02T00:30:01Z"), 3233, 9588.69, 146, 168))
        activities.save(Activity(athleteId, ExternalSource.GARMIN, "g-2", ActivityType.RUN,
            Instant.parse("2026-08-02T00:30:05Z"), 3234, 9588.69, 146, 168))
        readClient.activities = listOf(oauthActivity())

        val result = service.importRange(window)

        assertThat(result.skippedAmbiguous).isEqualTo(1)
        assertThat(result.imported).isZero()
        assertThat(activities.count()).isEqualTo(2)
        assertThat(result.items.single().reasonCode).isEqualTo("MULTIPLE_CANDIDATE_ACTIVITIES_2")
    }

    @Test
    fun `an activity Intervals received from Garmin is left to the enrichment path`() {
        readClient.activities = listOf(garminSourcedActivity("24413424790"))

        val result = service.importRange(window)

        assertThat(result.skippedGarminSourced).isEqualTo(1)
        assertThat(activities.count()).isZero()
        assertThat(result.items.single().reasonCode).isEqualTo("GARMIN_UPSTREAM_NOT_INGESTED")
    }

    @Test
    fun `a Garmin-sourced activity whose Garmin row exists is linked to it`() {
        val garmin = activities.save(
            Activity(
                athletes.defaultAthlete.id, ExternalSource.GARMIN, "24413424790", ActivityType.RUN,
                Instant.parse("2026-09-18T23:00:00Z"), 3600, 9920.0, 150, 175,
            ),
        )
        readClient.activities = listOf(garminSourcedActivity("24413424790"))

        val result = service.importRange(window)

        assertThat(result.linkedExisting).isEqualTo(1)
        assertThat(enrichment.findLink(garmin.id, ExternalSource.INTERVALS_ICU)!!.externalActivityId)
            .isEqualTo("i187812124")
        assertThat(activities.count()).isEqualTo(1)
    }

    @Test
    fun `a type with no RunningAI equivalent is reported, never approximated`() {
        readClient.activities = listOf(oauthActivity(id = "i180579412", type = "Ride"))

        val result = service.importRange(window)

        assertThat(result.skippedUnsupportedType).isEqualTo(1)
        assertThat(activities.count()).isZero()
        assertThat(result.items.single().reasonCode).isEqualTo("NO_ACTIVITY_TYPE_FOR_Ride")
    }

    @Test
    fun `an activity the API has no data for is recorded as such, not as a failure`() {
        readClient.activities = listOf(stravaStub())

        val result = service.importRange(window)

        assertThat(result.skippedNoSourceData).isEqualTo(1)
        assertThat(result.failed).isZero()
        assertThat(result.items.single().reasonCode).isEqualTo("SOURCE_DATA_UNAVAILABLE")
        // the Strava identity of the session is still recorded, for the archive recovery that follows
        assertThat(result.items.single().sourceLabel).isEqualTo("STRAVA")
    }

    @Test
    fun `a rate limit stops the run instead of walking the rest of the list into it`() {
        readClient.activities = listOf(
            oauthActivity(id = "i1", startLocal = "2026-08-02T09:30:01", startUtc = "2026-08-02T00:30:01Z"),
            oauthActivity(id = "i2", startLocal = "2026-08-03T09:30:01", startUtc = "2026-08-03T00:30:01Z"),
            oauthActivity(id = "i3", startLocal = "2026-08-04T09:30:01", startUtc = "2026-08-04T00:30:01Z"),
        )
        readClient.failStreamsWith = IntervalsException(IntervalsException.Reason.RATE_LIMITED, 429, "scripted 429")

        val result = service.importRange(window)

        assertThat(result.stopReason).isEqualTo("INTERVALS_RATE_LIMITED")
        assertThat(readClient.streamCalls).hasSize(1)
        assertThat(result.failed).isEqualTo(1)
        assertThat(result.processed).isEqualTo(1)
    }

    @Test
    fun `a failed activity is retried by the next invocation, and the attempts are counted`() {
        readClient.activities = listOf(oauthActivity())
        readClient.failStreamsWith = IntervalsException(IntervalsException.Reason.UPSTREAM_ERROR, 502, "scripted 502")
        val first = service.importRange(window)
        assertThat(first.failed).isEqualTo(1)

        readClient.failStreamsWith = null
        readClient.streams["i180579445"] = streamPayload(120)
        val second = service.importRange(window)

        assertThat(second.refreshed + second.imported).isEqualTo(1)
        assertThat(service.records().single().attemptCount).isEqualTo(2)
        assertThat(activities.count()).isEqualTo(1)
    }

    @Test
    fun `a dry run reports what would happen and writes nothing`() {
        readClient.activities = listOf(oauthActivity())

        val result = service.importRange(window.copy(dryRun = true))

        assertThat(result.imported).isEqualTo(1)
        assertThat(activities.count()).isZero()
        assertThat(service.records()).isEmpty()
        assertThat(readClient.streamCalls).isEmpty()
    }

    @Test
    fun `an import refuses to start while a publishing switch is on`() {
        System.setProperty("running-ai.workout-publishing.enabled", "true")
        try {
            assertThatThrownBy { service.importRange(window) }
                .isInstanceOf(UnsafeIntervalsImportRuntimeException::class.java)
        } finally {
            System.clearProperty("running-ai.workout-publishing.enabled")
        }
    }

    private fun counts(): Map<String, Int> = listOf(
        "activity", "activity_sample", "activity_detail", "activity_lap", "activity_zone",
        "activity_analysis", "activity_raw_payload", "intervals_raw_payload", "activity_intervals_metrics",
        "activity_source_link",
    ).associateWith { jdbc.queryForObject("select count(*) from $it", Int::class.java)!! }
}
