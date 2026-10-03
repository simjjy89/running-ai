package com.runningai.backfill

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.runningai.activity.ActivityRepository
import com.runningai.activity.ActivityType
import com.runningai.activity.ExternalSource
import com.runningai.activity.detail.ActivityDetailStore
import com.runningai.activity.detail.DetailPayloadType
import com.runningai.activity.detail.SampleCompleteness
import com.runningai.analysis.ActivityAnalysis
import com.runningai.analysis.ActivityAnalysisStore
import com.runningai.analysis.IntervalMetricsCalculator
import com.runningai.analysis.IntervalStructureExtractor
import com.runningai.analysis.LapMetricsCalculator
import com.runningai.analysis.RUNNING_ANALYSIS_VERSION
import com.runningai.analysis.RunningActivityAnalysisService
import com.runningai.analysis.SessionMetricsCalculator
import com.runningai.analysis.ThresholdExposureCalculator
import com.runningai.analysis.ZoneExposureCalculator
import com.runningai.athlete.AthleteIntensityProfileRepository
import com.runningai.athlete.AthleteService
import com.runningai.enrichment.IntervalsEnrichmentStore
import com.runningai.enrichment.MatchEvidence
import com.runningai.enrichment.MatchMethod
import com.runningai.integration.garmin.GarminActivityDetailSource
import com.runningai.integration.garmin.GarminActivityPart
import com.runningai.integration.garmin.GarminActivitySource
import com.runningai.integration.garmin.GarminConnectorException
import com.runningai.integration.garmin.GarminRecoveryClient
import com.runningai.integration.garmin.GarminSyncState
import com.runningai.integration.garmin.GarminSyncStateRepository
import com.runningai.integration.intervals.IntervalsRateLimit
import com.runningai.integration.intervals.IntervalsReadClient
import com.runningai.integration.intervals.IntervalsReadResponse
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
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/**
 * The historical backfill end to end over the real schema (H2 + Flyway V19), real ingestion, detail
 * mappers, analysis engine, matcher and stores. Every external boundary is a scripted fake that also
 * counts calls - which is how the no-retry, resume-without-refetch and sync-state-isolation claims are
 * proven. Not `@Transactional`: checkpoints must be observed as committed.
 */
@SpringBootTest(
    properties = [
        "running-ai.garmin.connector.base-url=http://127.0.0.1:9",
        "running-ai.intervals.api-key=",
        "running-ai.intervals.base-url=http://127.0.0.1:9",
        "running-ai.historical-backfill.page-size=2",
        "running-ai.historical-backfill.max-pages=4",
        "running-ai.historical-backfill.max-activities=5",
        "running-ai.historical-backfill.activity-delay=0s",
        "running-ai.garmin.recovery-sync.backfill-delay=0s",
    ],
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HistoricalBackfillServiceTest {

    class FakeActivitySource : GarminActivitySource {
        var pages: List<List<JsonNode>> = emptyList()
        val calls = mutableListOf<Int>()
        var failNextWith: GarminConnectorException? = null

        override fun fetchActivities(start: Int, limit: Int): List<JsonNode> {
            failNextWith?.let {
                failNextWith = null
                throw it
            }
            calls += start
            return pages.getOrElse(start / limit) { emptyList() }
        }
    }

    class FakeDetailSource : GarminActivityDetailSource {
        val responses = mutableMapOf<Pair<String, GarminActivityPart>, () -> JsonNode>()
        val calls = mutableListOf<Pair<String, GarminActivityPart>>()

        override fun fetch(part: GarminActivityPart, garminActivityId: String): JsonNode {
            calls += garminActivityId to part
            return (responses[garminActivityId to part] ?: error("no scripted $part for $garminActivityId")).invoke()
        }
    }

    class FakeIntervalsReadClient : IntervalsReadClient {
        val listCalls = mutableListOf<Pair<LocalDate, LocalDate>>()
        var listResponse: (LocalDate, LocalDate) -> JsonNode = { _, _ -> EMPTY_ARRAY }
        var wellnessResponse: () -> JsonNode = { EMPTY_ARRAY }
        var wellnessCalls = 0
        var activityDetailCalls = 0

        override fun listActivities(oldest: LocalDate, newest: LocalDate): IntervalsReadResponse {
            listCalls += oldest to newest
            return IntervalsReadResponse(listResponse(oldest, newest), IntervalsRateLimit(null, null, null))
        }

        override fun getActivity(intervalsActivityId: String): IntervalsReadResponse = getActivity(intervalsActivityId, false)

        override fun getActivity(intervalsActivityId: String, includeIntervals: Boolean): IntervalsReadResponse {
            activityDetailCalls++
            return IntervalsReadResponse(EMPTY_ARRAY, IntervalsRateLimit(null, null, null))
        }

        override fun getWellness(oldest: LocalDate, newest: LocalDate): IntervalsReadResponse {
            wellnessCalls++
            return IntervalsReadResponse(wellnessResponse(), IntervalsRateLimit(null, null, null))
        }

        companion object {
            val EMPTY_ARRAY: JsonNode = ObjectMapper().createArrayNode()
        }
    }

    class FakeRecoveryClient : GarminRecoveryClient {
        val dates = mutableListOf<LocalDate>()
        var failOn: LocalDate? = null
        private val json = ObjectMapper()

        override fun fetchDay(date: LocalDate): JsonNode {
            if (date == failOn) {
                throw GarminConnectorException(GarminConnectorException.Reason.RATE_LIMITED, 429, "scripted 429")
            }
            dates += date
            // One real metric so a row is stored (a day with no value at all stores nothing, by design)
            return json.createObjectNode().put("date", date.toString()).apply {
                putObject("metrics").putObject("restingHeartRate").put("status", "OK")
                    .putObject("data").put("value", 55)
            }
        }
    }

    /** The real analysis engine, with a scripted per-activity failure switch for the fail-fast tests. */
    class ScriptedAnalysisService(
        activities: ActivityRepository,
        detail: ActivityDetailStore,
        profiles: AthleteIntensityProfileRepository,
        sessionMetrics: SessionMetricsCalculator,
        zoneExposure: ZoneExposureCalculator,
        thresholdExposure: ThresholdExposureCalculator,
        lapMetrics: LapMetricsCalculator,
        extractor: IntervalStructureExtractor,
        intervalMetrics: IntervalMetricsCalculator,
        store: ActivityAnalysisStore,
        clock: Clock,
    ) : RunningActivityAnalysisService(activities, detail, profiles, sessionMetrics, zoneExposure,
        thresholdExposure, lapMetrics, extractor, intervalMetrics, store, clock) {

        val failFor = mutableSetOf<Long>()
        val analysedIds = mutableListOf<Long>()

        override fun analyse(activityId: Long): ActivityAnalysis {
            check(activityId !in failFor) { "scripted analysis failure" }
            analysedIds += activityId
            return super.analyse(activityId)
        }
    }

    @TestConfiguration
    class Fakes {
        @Bean @Primary fun fakeActivitySource() = FakeActivitySource()

        @Bean @Primary fun fakeDetailSource() = FakeDetailSource()

        @Bean @Primary fun fakeIntervalsReadClient() = FakeIntervalsReadClient()

        @Bean @Primary fun fakeRecoveryClient() = FakeRecoveryClient()

        @Bean
        @Primary
        fun scriptedAnalysis(
            activities: ActivityRepository,
            detail: ActivityDetailStore,
            profiles: AthleteIntensityProfileRepository,
            sessionMetrics: SessionMetricsCalculator,
            zoneExposure: ZoneExposureCalculator,
            thresholdExposure: ThresholdExposureCalculator,
            lapMetrics: LapMetricsCalculator,
            extractor: IntervalStructureExtractor,
            intervalMetrics: IntervalMetricsCalculator,
            store: ActivityAnalysisStore,
            clock: Clock,
        ) = ScriptedAnalysisService(activities, detail, profiles, sessionMetrics, zoneExposure,
            thresholdExposure, lapMetrics, extractor, intervalMetrics, store, clock)
    }

    @Autowired private lateinit var service: HistoricalBackfillService
    @Autowired private lateinit var store: HistoricalBackfillStore
    @Autowired private lateinit var activitySource: FakeActivitySource
    @Autowired private lateinit var detailSource: FakeDetailSource
    @Autowired private lateinit var intervals: FakeIntervalsReadClient
    @Autowired private lateinit var recovery: FakeRecoveryClient
    @Autowired private lateinit var analysis: ScriptedAnalysisService
    @Autowired private lateinit var activities: ActivityRepository
    @Autowired private lateinit var detailStore: ActivityDetailStore
    @Autowired private lateinit var enrichmentStore: IntervalsEnrichmentStore
    @Autowired private lateinit var syncStates: GarminSyncStateRepository
    @Autowired private lateinit var athleteService: AthleteService
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var mockMvc: MockMvc

    private val json = ObjectMapper()
    private var athleteId: Long = 0

    // Window fixed by the request: 2026-07-06 .. 2026-10-03 (90 days, athlete tz Asia/Seoul)
    private val endDate: LocalDate = LocalDate.parse("2026-10-03")
    private val request = HistoricalBackfillRequest(endDate = endDate, days = 90)

    private val idOld = "910000000"      // 2026-07-01 local: before the window
    private val idA1 = "910000001"       // 2026-07-10 local: oldest in-window run
    private val idA2 = "910000002"       // 2026-09-15 local: unsupported type
    private val idA3 = "910000003"       // 2026-10-01 local: newest in-window run

    @BeforeEach
    fun setUp() {
        athleteId = athleteService.defaultAthlete.id
        activitySource.pages = emptyList()
        activitySource.calls.clear()
        activitySource.failNextWith = null
        detailSource.responses.clear()
        detailSource.calls.clear()
        intervals.listCalls.clear()
        intervals.listResponse = { _, _ -> FakeIntervalsReadClient.EMPTY_ARRAY }
        intervals.wellnessResponse = { FakeIntervalsReadClient.EMPTY_ARRAY }
        intervals.wellnessCalls = 0
        intervals.activityDetailCalls = 0
        recovery.dates.clear()
        recovery.failOn = null
        analysis.failFor.clear()
        analysis.analysedIds.clear()
    }

    @AfterEach
    fun cleanUp() {
        listOf(
            "historical_backfill_activity", "historical_backfill_run",
            "activity_analysis_interval", "activity_analysis_interval_group", "activity_analysis",
            "activity_detail_collection", "activity_sample", "activity_zone", "activity_lap", "activity_detail",
            "activity_raw_payload", "activity_raw",
            "activity_intervals_metrics", "activity_source_link", "intervals_raw_payload", "intervals_fitness_daily",
            "garmin_recovery_daily", "garmin_sync_state", "activity",
        ).forEach { jdbc.update("delete from $it") }
    }

    // ---- fixture builders ----

    private fun fixture(path: String): JsonNode = json.readTree(javaClass.getResourceAsStream(path))

    private fun garminItem(id: String, startGmt: String, typeKey: String = "running"): ObjectNode {
        val item = fixture("/fixtures/garmin/running-activity.json").deepCopy<ObjectNode>()
        item.put("activityId", id.toLong())
        item.put("startTimeGMT", startGmt)
        (item.get("activityType") as ObjectNode).put("typeKey", typeKey)
        return item
    }

    private fun fullSamples(id: String): ObjectNode =
        fixture("/fixtures/garmin/detail/samples-outdoor.LIVE_SHAPE.ANONYMISED.json").deepCopy<ObjectNode>().apply {
            put("activityId", id.toLong())
            put("totalMetricsCount", get("metricsCount").intValue()) // native count == stored count -> FULL
        }

    private fun downsampledSamples(id: String): ObjectNode =
        fixture("/fixtures/garmin/detail/samples-outdoor.LIVE_SHAPE.ANONYMISED.json").deepCopy<ObjectNode>()
            .apply { put("activityId", id.toLong()) } // fixture ships 3 of 6 -> DOWNSAMPLED

    private fun unknownSamples(id: String): ObjectNode =
        fullSamples(id).apply { remove("totalMetricsCount") }

    private fun scriptDetail(id: String, samples: JsonNode = fullSamples(id)) {
        detailSource.responses[id to GarminActivityPart.DETAIL] =
            { json.createObjectNode().put("activityId", id.toLong()) }
        detailSource.responses[id to GarminActivityPart.SPLITS] =
            { fixture("/fixtures/garmin/detail/splits.LIVE_SHAPE.ANONYMISED.json") }
        detailSource.responses[id to GarminActivityPart.HR_ZONES] =
            { fixture("/fixtures/garmin/detail/hr-zones.LIVE_SHAPE.ANONYMISED.json") }
        detailSource.responses[id to GarminActivityPart.POWER_ZONES] = { json.createArrayNode() }
        detailSource.responses[id to GarminActivityPart.SAMPLES] = { samples }
    }

    private fun intervalsItem(intervalsId: String, garminId: String): ObjectNode =
        (fixture("/fixtures/intervals/activities-list.LIVE_SHAPE.ANONYMISED.json")[0] as ObjectNode)
            .deepCopy().apply {
                put("id", intervalsId)
                put("external_id", garminId)
                put("source", "GARMIN_CONNECT")
                put("type", "Run")
                put("start_date", "2026-09-30T21:00:00Z")
                put("elapsed_time", 3600)
                put("distance", 10000.0)
                put("icu_training_load", 50)
                put("icu_intensity", 80.0)
                put("icu_ctl", 10.5)
                put("icu_atl", 12.25)
            }

    private fun wellnessDay(date: String): ObjectNode =
        (fixture("/fixtures/intervals/wellness.LIVE_SHAPE.ANONYMISED.json")[0] as ObjectNode)
            .deepCopy().apply { put("id", date) }

    /** The baseline scenario: A3+unsupported A2 on page 0, a duplicate A3 + A1 on page 1, boundary on page 2. */
    private fun setupHappyPath() {
        activitySource.pages = listOf(
            listOf(garminItem(idA3, "2026-09-30 21:00:00"), garminItem(idA2, "2026-09-15 01:00:00", typeKey = "breathwork")),
            listOf(garminItem(idA3, "2026-09-30 21:00:00"), garminItem(idA1, "2026-07-09 21:00:00")),
            listOf(garminItem(idOld, "2026-06-30 21:00:00")),
        )
        scriptDetail(idA1)
        scriptDetail(idA3)
        intervals.listResponse = { _, newest ->
            if (newest == endDate) json.createArrayNode().add(intervalsItem("i91000003", idA3)) else FakeIntervalsReadClient.EMPTY_ARRAY
        }
        intervals.wellnessResponse = {
            json.createArrayNode().add(wellnessDay("2026-09-20")).add(wellnessDay("2026-10-01"))
        }
    }

    private fun activityIdOf(garminId: String): Long =
        activities.findByExternalSourceAndExternalId(ExternalSource.GARMIN, garminId).orElseThrow().id

    private fun count(table: String): Int = jdbc.queryForObject("SELECT COUNT(*) FROM $table", Int::class.java)!!

    // ---- tests ----

    @Test
    fun `full pipeline - discovery, detail, analysis, intervals, fitness, recovery, verify - COMPLETED`() {
        setupHappyPath()
        val syncStateBefore = syncStates.save(GarminSyncState(athleteId,
            Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-02T00:00:00Z")))

        val result = service.start(request)

        assertThat(result.status).isEqualTo(BackfillRunStatus.COMPLETED)
        assertThat(result.phase).isEqualTo(BackfillPhase.COMPLETED)
        assertThat(result.startDate).isEqualTo(LocalDate.parse("2026-07-06"))
        assertThat(result.endDate).isEqualTo(endDate)
        assertThat(result.targetActivities).isEqualTo(2)
        assertThat(result.completedActivities).isEqualTo(2)
        assertThat(result.skippedUnsupported).isEqualTo(1)
        assertThat(result.detailRefetched).isEqualTo(2)
        assertThat(result.detailSkippedAlreadyComplete).isEqualTo(0)
        assertThat(result.detailFull).isEqualTo(2)
        assertThat(result.detailNoSamples).isEqualTo(0)
        assertThat(result.analysisComplete + result.analysisPartial).isEqualTo(2)
        assertThat(result.intervalsMatched).isEqualTo(1)
        assertThat(result.intervalsUnmatched).isEqualTo(1)
        assertThat(result.intervalsAmbiguous).isEqualTo(0)
        assertThat(result.fitnessDays).isEqualTo(2)
        assertThat(result.recoveryDays).isEqualTo(28)
        assertThat(result.stopReason).isNull()

        // call shape: 3 discovery pages from offset 0; 5 detail parts per refetched activity; 3 Intervals
        // windows + 1 wellness; 28 recovery days newest-first; never an Intervals per-activity GET
        assertThat(activitySource.calls).containsExactly(0, 2, 4)
        assertThat(detailSource.calls).hasSize(10)
        assertThat(detailSource.calls.map { it.first }.distinct()).containsExactlyInAnyOrder(idA1, idA3)
        assertThat(intervals.listCalls).containsExactly(
            LocalDate.parse("2026-07-06") to LocalDate.parse("2026-08-05"),
            LocalDate.parse("2026-08-06") to LocalDate.parse("2026-09-05"),
            LocalDate.parse("2026-09-06") to LocalDate.parse("2026-10-03"),
        )
        assertThat(intervals.wellnessCalls).isEqualTo(1)
        assertThat(intervals.activityDetailCalls).isEqualTo(0)
        assertThat(recovery.dates).hasSize(28)
        assertThat(recovery.dates.first()).isEqualTo(endDate)
        assertThat(recovery.dates.last()).isEqualTo(LocalDate.parse("2026-09-06"))

        // oldest -> newest processing (A1 before A3)
        assertThat(detailSource.calls.first().first).isEqualTo(idA1)

        // storage: 2 supported activities (the duplicate listing created no second row), raw kept for the
        // unsupported one, intervals link + metrics on the matched activity, analysis V1 on both
        assertThat(count("activity")).isEqualTo(2)
        assertThat(count("activity_raw")).isEqualTo(3)
        assertThat(activities.findByExternalSourceAndExternalId(ExternalSource.GARMIN, idA1).orElseThrow().activityType)
            .isEqualTo(ActivityType.RUN)
        val a3 = activityIdOf(idA3)
        assertThat(enrichmentStore.findLink(a3, ExternalSource.INTERVALS_ICU)!!.matchMethod).isEqualTo(MatchMethod.SOURCE_ID)
        assertThat(enrichmentStore.findMetrics(a3)!!.trainingLoad).isEqualTo(50)
        assertThat(count("activity_analysis")).isEqualTo(2)
        assertThat(jdbc.queryForObject(
            "select count(*) from activity_analysis where analysis_version = '$RUNNING_ANALYSIS_VERSION'",
            Int::class.java)).isEqualTo(2)
        assertThat(count("intervals_fitness_daily")).isEqualTo(2)
        assertThat(count("garmin_recovery_daily")).isEqualTo(28)

        // the incremental-sync checkpoint is untouched (§7/§16)
        val after = syncStates.findById(syncStateBefore.id).orElseThrow()
        assertThat(after.highWaterStartedAt).isEqualTo(Instant.parse("2026-09-01T00:00:00Z"))
        assertThat(after.lastSuccessfulSyncAt).isEqualTo(Instant.parse("2026-09-02T00:00:00Z"))
    }

    @Test
    fun `a second run over the same window is idempotent and skips every Garmin detail refetch`() {
        setupHappyPath()
        service.start(request)
        val sampleRows = count("activity_sample")
        detailSource.calls.clear()
        analysis.analysedIds.clear()

        val second = service.start(request)

        assertThat(second.status).isEqualTo(BackfillRunStatus.COMPLETED)
        assertThat(second.detailRefetched).isEqualTo(0)
        assertThat(second.detailSkippedAlreadyComplete).isEqualTo(2)
        assertThat(detailSource.calls).isEmpty()
        assertThat(analysis.analysedIds).isEmpty() // fresh V1 analysis + unchanged detail -> reused
        // no duplicates anywhere
        assertThat(count("activity")).isEqualTo(2)
        assertThat(count("activity_raw")).isEqualTo(3)
        assertThat(count("activity_detail")).isEqualTo(2)
        assertThat(count("activity_sample")).isEqualTo(sampleRows)
        assertThat(count("activity_analysis")).isEqualTo(2)
        assertThat(count("activity_source_link")).isEqualTo(1)
        assertThat(count("activity_intervals_metrics")).isEqualTo(1)
        assertThat(count("intervals_fitness_daily")).isEqualTo(2)
        assertThat(jdbc.queryForObject(
            "select count(*) from intervals_raw_payload where payload_type='ACTIVITY'", Int::class.java)).isEqualTo(1)
    }

    @Test
    fun `an empty Garmin history completes with zero targets`() {
        activitySource.pages = listOf(emptyList())

        val result = service.start(request)

        assertThat(result.status).isEqualTo(BackfillRunStatus.COMPLETED)
        assertThat(result.targetActivities).isEqualTo(0)
        assertThat(result.recoveryDays).isEqualTo(28)
    }

    @Test
    fun `a Garmin rate limit during discovery pauses, and resume restarts discovery from offset 0`() {
        setupHappyPath()
        activitySource.failNextWith = GarminConnectorException(GarminConnectorException.Reason.RATE_LIMITED, 429, "429")

        val paused = service.start(request)

        assertThat(paused.status).isEqualTo(BackfillRunStatus.PAUSED)
        assertThat(paused.phase).isEqualTo(BackfillPhase.GARMIN_DISCOVERY)
        assertThat(paused.stopReason).isEqualTo("GARMIN_RATE_LIMITED")
        assertThat(activitySource.calls).isEmpty()

        val resumed = service.resume(paused.runId)

        assertThat(resumed.status).isEqualTo(BackfillRunStatus.COMPLETED)
        assertThat(activitySource.calls.first()).isEqualTo(0) // never a stored offset (§14)
    }

    @Test
    fun `exceeding the activity cap pauses as BACKFILL_DISCOVERY_LIMIT_EXCEEDED`() {
        // three full pages of in-window runs: the 6th in-window activity exceeds max-activities=5
        activitySource.pages = listOf(
            listOf(garminItem("920000006", "2026-09-26 21:00:00"), garminItem("920000005", "2026-09-25 21:00:00")),
            listOf(garminItem("920000004", "2026-09-24 21:00:00"), garminItem("920000003", "2026-09-23 21:00:00")),
            listOf(garminItem("920000002", "2026-09-22 21:00:00"), garminItem("920000001", "2026-09-21 21:00:00")),
        )

        val result = service.start(request)

        assertThat(result.status).isEqualTo(BackfillRunStatus.PAUSED)
        assertThat(result.stopReason).isEqualTo("BACKFILL_DISCOVERY_LIMIT_EXCEEDED")
    }

    @Test
    fun `exceeding the page cap pauses instead of silently truncating`() {
        // four full pages that never reach the window start (everything newer than the window end)
        val newer = { id: String -> garminItem(id, "2026-10-05 21:00:00") }
        activitySource.pages = listOf(
            listOf(newer("930000001"), newer("930000002")),
            listOf(newer("930000003"), newer("930000004")),
            listOf(newer("930000005"), newer("930000006")),
            listOf(newer("930000007"), newer("930000008")),
        )

        val result = service.start(request)

        assertThat(result.status).isEqualTo(BackfillRunStatus.PAUSED)
        assertThat(result.stopReason).isEqualTo("BACKFILL_DISCOVERY_LIMIT_EXCEEDED")
    }

    @Test
    fun `an unplaceable discovery item pauses with the mapping code`() {
        activitySource.pages = listOf(listOf(fixture("/fixtures/garmin/missing-start-time.json")))

        val result = service.start(request)

        assertThat(result.status).isEqualTo(BackfillRunStatus.PAUSED)
        assertThat(result.stopReason).isEqualTo("GARMIN_ACTIVITY_START_TIME_MISSING")
    }

    @Test
    fun `a DOWNSAMPLED stream pauses the run and is refetched to FULL on resume`() {
        setupHappyPath()
        scriptDetail(idA1, downsampledSamples(idA1))

        val paused = service.start(request)

        assertThat(paused.status).isEqualTo(BackfillRunStatus.PAUSED)
        assertThat(paused.stopReason).isEqualTo("SAMPLE_STREAM_DOWNSAMPLED")
        assertThat(paused.phase).isEqualTo(BackfillPhase.GARMIN_DETAIL_ANALYSIS)
        // A3 (newer) was never attempted: oldest-first stopped at A1
        assertThat(detailSource.calls.map { it.first }.toSet()).containsExactly(idA1)

        // operator reviewed the setting; the stream now comes back complete
        scriptDetail(idA1, fullSamples(idA1))
        val resumed = service.resume(paused.runId)

        assertThat(resumed.status).isEqualTo(BackfillRunStatus.COMPLETED)
        assertThat(resumed.detailFull).isEqualTo(2)
        val completeness = detailStore.part(activityIdOf(idA1), DetailPayloadType.ACTIVITY_DETAILS_STREAM)!!
            .sampleFidelity!!.completeness
        assertThat(completeness).isEqualTo(SampleCompleteness.FULL)
    }

    @Test
    fun `an UNKNOWN-fidelity stream pauses the run`() {
        setupHappyPath()
        scriptDetail(idA1, unknownSamples(idA1))

        val result = service.start(request)

        assertThat(result.status).isEqualTo(BackfillRunStatus.PAUSED)
        assertThat(result.stopReason).isEqualTo("SAMPLE_STREAM_FIDELITY_UNKNOWN")
    }

    @Test
    fun `a legitimately empty sample stream is NO_SAMPLE_STREAM, not a failure`() {
        setupHappyPath()
        val empty = json.createObjectNode().put("activityId", idA1.toLong())
            .put("metricsCount", 0).put("totalMetricsCount", 0)
            .apply { putArray("metricDescriptors"); putArray("activityDetailMetrics") }
        scriptDetail(idA1, empty)

        val result = service.start(request)

        assertThat(result.status).isEqualTo(BackfillRunStatus.COMPLETED)
        assertThat(result.detailNoSamples).isEqualTo(1)
        assertThat(result.detailFull).isEqualTo(1)
        assertThat(result.analysisComplete + result.analysisPartial).isGreaterThanOrEqualTo(1)
    }

    @Test
    fun `a part mapping failure pauses instead of silently moving on`() {
        setupHappyPath()
        detailSource.responses[idA1 to GarminActivityPart.SPLITS] = {
            val payload = json.createObjectNode()
            payload.set<ObjectNode>("lapDTOs", json.createObjectNode().put("broken", true))
            payload
        }

        val result = service.start(request)

        assertThat(result.status).isEqualTo(BackfillRunStatus.PAUSED)
        assertThat(result.stopReason).isEqualTo("DETAIL_COLLECTION_PARTIAL")
    }

    @Test
    fun `a Garmin auth failure in the detail stage pauses immediately with no further calls`() {
        setupHappyPath()
        detailSource.responses[idA1 to GarminActivityPart.DETAIL] =
            { throw GarminConnectorException(GarminConnectorException.Reason.AUTH_REQUIRED, 401, "401") }

        val result = service.start(request)

        assertThat(result.status).isEqualTo(BackfillRunStatus.PAUSED)
        assertThat(result.stopReason).isEqualTo("GARMIN_AUTH_REQUIRED")
        assertThat(detailSource.calls).hasSize(1) // the failing call itself; nothing after, no retry
    }

    @Test
    fun `an analysis failure pauses, and resume retries analysis locally without refetching detail`() {
        setupHappyPath()
        // First run stores everything; then the analyses are dropped and A1's recomputation is scripted
        // to fail, so a fresh run must recompute (no reuse) while the stored detail still passes the skip
        // gate: the pause has to come from the analysis stage alone.
        service.start(request)
        cleanBackfillStateOnly()
        listOf("activity_analysis_interval", "activity_analysis_interval_group", "activity_analysis")
            .forEach { jdbc.update("delete from $it") }
        analysis.failFor.add(activityIdOf(idA1))
        detailSource.calls.clear()

        val paused = service.start(request)

        assertThat(paused.status).isEqualTo(BackfillRunStatus.PAUSED)
        assertThat(paused.stopReason).isEqualTo("ANALYSIS_FAILED")
        assertThat(detailSource.calls).isEmpty() // detail was already FULL: nothing was refetched

        analysis.failFor.clear()
        val resumed = service.resume(paused.runId)

        assertThat(resumed.status).isEqualTo(BackfillRunStatus.COMPLETED)
        assertThat(detailSource.calls).isEmpty() // resume never refetched detail (§65)
    }

    @Test
    fun `an Intervals rate limit pauses after the Garmin stages, and resume redoes only Intervals`() {
        setupHappyPath()
        var intervalsCalls = 0
        intervals.listResponse = { _, _ ->
            intervalsCalls++
            throw com.runningai.integration.intervals.IntervalsException(
                com.runningai.integration.intervals.IntervalsException.Reason.RATE_LIMITED, 429, "429")
        }

        val paused = service.start(request)

        assertThat(paused.status).isEqualTo(BackfillRunStatus.PAUSED)
        assertThat(paused.phase).isEqualTo(BackfillPhase.INTERVALS_ACTIVITIES)
        assertThat(paused.stopReason).isEqualTo("INTERVALS_RATE_LIMITED")
        assertThat(intervalsCalls).isEqualTo(1) // no retry
        assertThat(paused.completedActivities).isEqualTo(2) // Garmin work kept

        intervals.listResponse = { _, newest ->
            if (newest == endDate) json.createArrayNode().add(intervalsItem("i91000003", idA3))
            else FakeIntervalsReadClient.EMPTY_ARRAY
        }
        detailSource.calls.clear()
        val resumed = service.resume(paused.runId)

        assertThat(resumed.status).isEqualTo(BackfillRunStatus.COMPLETED)
        assertThat(detailSource.calls).isEmpty() // resume started at the Intervals phase
        assertThat(resumed.intervalsMatched).isEqualTo(1)
    }

    @Test
    fun `an Intervals source-link conflict is a data-integrity pause`() {
        setupHappyPath()
        // the Intervals activity is already linked to a different local activity
        val other = activities.save(com.runningai.activity.Activity(athleteId, ExternalSource.GARMIN, "999999999",
            ActivityType.RUN, Instant.parse("2026-05-01T00:00:00Z"), 1800, null, null, null))
        enrichmentStore.upsertLink(other.id, ExternalSource.INTERVALS_ICU, "i91000003", MatchMethod.SOURCE_ID,
            MatchEvidence(null, null, null), Instant.parse("2026-05-01T00:00:00Z"))

        val result = service.start(request)

        assertThat(result.status).isEqualTo(BackfillRunStatus.PAUSED)
        assertThat(result.stopReason).isEqualTo("INTERVALS_LINK_CONFLICT")
    }

    @Test
    fun `two Intervals candidates for the same Garmin id record AMBIGUOUS and the run continues`() {
        setupHappyPath()
        intervals.listResponse = { _, newest ->
            if (newest == endDate) {
                json.createArrayNode()
                    .add(intervalsItem("i91000003", idA3))
                    .add(intervalsItem("i91000003b", idA3))
            } else {
                FakeIntervalsReadClient.EMPTY_ARRAY
            }
        }

        val result = service.start(request)

        assertThat(result.status).isEqualTo(BackfillRunStatus.COMPLETED)
        assertThat(result.intervalsAmbiguous).isEqualTo(1)
        assertThat(result.intervalsUnmatched).isEqualTo(1)
        assertThat(count("activity_source_link")).isEqualTo(0)
    }

    @Test
    fun `a recovery failure pauses at its date, and resume never re-requests completed days`() {
        setupHappyPath()
        val failDate = endDate.minusDays(2)
        recovery.failOn = failDate

        val paused = service.start(request)

        assertThat(paused.status).isEqualTo(BackfillRunStatus.PAUSED)
        assertThat(paused.phase).isEqualTo(BackfillPhase.GARMIN_RECOVERY)
        assertThat(paused.stopReason).isEqualTo("GARMIN_RATE_LIMITED")
        assertThat(paused.recoveryDays).isEqualTo(2)
        assertThat(recovery.dates).containsExactly(endDate, endDate.minusDays(1))

        recovery.failOn = null
        recovery.dates.clear()
        val resumed = service.resume(paused.runId)

        assertThat(resumed.status).isEqualTo(BackfillRunStatus.COMPLETED)
        assertThat(resumed.recoveryDays).isEqualTo(28)
        assertThat(recovery.dates.first()).isEqualTo(failDate) // continues at the failed date
        assertThat(recovery.dates).doesNotContain(endDate, endDate.minusDays(1)) // completed days untouched
    }

    @Test
    fun `a RUNNING run in the database refuses a second start`() {
        val run = store.createRun(athleteId, endDate.minusDays(89), endDate, 90, Instant.parse("2026-10-03T00:00:00Z"))
        assertThat(run.status).isEqualTo(BackfillRunStatus.RUNNING)

        assertThatThrownBy { service.start(request) }
            .isInstanceOf(HistoricalBackfillAlreadyRunningException::class.java)
    }

    @Test
    fun `resuming a COMPLETED run refuses deterministically with zero network calls`() {
        setupHappyPath()
        val done = service.start(request)
        activitySource.calls.clear()
        detailSource.calls.clear()
        recovery.dates.clear()
        val wellnessBefore = intervals.wellnessCalls

        assertThatThrownBy { service.resume(done.runId) }
            .isInstanceOf(BackfillNotResumableException::class.java)
            .extracting { (it as BackfillNotResumableException).code }
            .isEqualTo("HISTORICAL_BACKFILL_ALREADY_COMPLETED")
        assertThat(activitySource.calls).isEmpty()
        assertThat(detailSource.calls).isEmpty()
        assertThat(recovery.dates).isEmpty()
        assertThat(intervals.wellnessCalls).isEqualTo(wellnessBefore)
    }

    @Test
    fun `a FAILED run is not resumable`() {
        val run = store.createRun(athleteId, endDate.minusDays(89), endDate, 90, Instant.parse("2026-10-03T00:00:00Z"))
        store.updateRun(requireNotNull(run.id)) {
            it.status = BackfillRunStatus.FAILED
            it.stopReason = "INTERNAL_TEST"
        }

        assertThatThrownBy { service.resume(requireNotNull(run.id)) }
            .isInstanceOf(BackfillNotResumableException::class.java)
            .extracting { (it as BackfillNotResumableException).code }
            .isEqualTo("HISTORICAL_BACKFILL_FAILED_NOT_RESUMABLE")
    }

    @Test
    fun `more than 90 days is rejected`() {
        assertThatThrownBy { service.start(HistoricalBackfillRequest(endDate = endDate, days = 91)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `the manual HTTP trigger runs the pipeline and reports the run`() {
        setupHappyPath()

        mockMvc.perform(post("/api/v1/historical-backfill").contentType(MediaType.APPLICATION_JSON)
            .content("""{"endDate":"2026-10-03","days":90}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.targetActivities").value(2))
            .andExpect(jsonPath("$.recoveryDays").value(28))
    }

    /** Wipes only the backfill bookkeeping so a fresh run sees the already-stored Garmin data. */
    private fun cleanBackfillStateOnly() {
        jdbc.update("delete from historical_backfill_activity")
        jdbc.update("delete from historical_backfill_run")
    }
}
