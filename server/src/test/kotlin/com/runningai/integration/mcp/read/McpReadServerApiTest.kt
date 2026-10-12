package com.runningai.integration.mcp.read

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.runningai.activity.Activity
import com.runningai.activity.ActivityRepository
import com.runningai.activity.ActivityType
import com.runningai.activity.ExternalSource
import com.runningai.analysis.ActivityAnalysis
import com.runningai.analysis.ActivityAnalysisStore
import com.runningai.analysis.AnalysisStatus
import com.runningai.analysis.HalfSplitMetrics
import com.runningai.analysis.LapMetrics
import com.runningai.analysis.RunningDynamicsMetrics
import com.runningai.analysis.SessionMetrics
import com.runningai.analysis.ThresholdExposure
import com.runningai.analysis.ZoneExposure
import com.runningai.activity.detail.SampleCompleteness
import com.runningai.athlete.AthleteIntensityProfileRequest
import com.runningai.athlete.AthleteIntensityProfileService
import com.runningai.athlete.AthleteService
import com.runningai.enrichment.IntervalsEnrichmentStore
import com.runningai.enrichment.IntervalsWellnessDay
import com.runningai.injury.InjuryReportRepository
import com.runningai.injury.InjuryTestFixtures
import com.runningai.integration.garmin.GarminRecoveryClient
import com.runningai.integration.intervals.IntervalsWorkoutClient
import com.runningai.integration.intervals.IntervalsWorkoutPublisher
import com.runningai.integration.intervals.WorkoutPublishApplicationService
import com.runningai.recovery.RecoveryDailyValues
import com.runningai.recovery.RecoveryRepository
import com.runningai.recovery.RecoverySnapshotService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The Phase 7C-2a read-only MCP mode end to end over the real Streamable HTTP endpoint: ONLY
 * `running-ai.mcp.read-enabled` is on (the write switch stays at its default false), the seven read
 * tools answer from seeded H2 rows, and the write adapter is structurally absent - not listed, and
 * a forced call fails. Every Garmin/Intervals transport bean is mocked and asserted untouched, and
 * the row counts of every seeded table are asserted unchanged by the tool calls.
 */
@SpringBootTest(
    properties = [
        "running-ai.mcp.read-enabled=true",
        "running-ai.garmin.connector.base-url=http://127.0.0.1:9",
        "running-ai.intervals.api-key=",
        "running-ai.intervals.base-url=http://127.0.0.1:9",
    ],
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class McpReadServerApiTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var athleteService: AthleteService
    @Autowired private lateinit var activities: ActivityRepository
    @Autowired private lateinit var analysisStore: ActivityAnalysisStore
    @Autowired private lateinit var recoverySnapshots: RecoverySnapshotService
    @Autowired private lateinit var recoveryRepository: RecoveryRepository
    @Autowired private lateinit var intervalsStore: IntervalsEnrichmentStore
    @Autowired private lateinit var profileService: AthleteIntensityProfileService
    @Autowired private lateinit var injuryReports: InjuryReportRepository
    @Autowired private lateinit var clock: Clock
    @Autowired private lateinit var jdbc: JdbcTemplate

    @MockitoBean private lateinit var publishService: WorkoutPublishApplicationService
    @MockitoBean private lateinit var publisher: IntervalsWorkoutPublisher
    @MockitoBean private lateinit var intervalsClient: IntervalsWorkoutClient
    @MockitoBean private lateinit var garminRecoveryClient: GarminRecoveryClient

    private val zone: ZoneId = ZoneId.of("Asia/Seoul")
    private val today: LocalDate get() = LocalDate.now(clock.withZone(zone))

    @AfterEach
    fun cleanUp() {
        verifyNoInteractions(publishService, publisher, intervalsClient, garminRecoveryClient)
        listOf(
            "activity_analysis_interval", "activity_analysis_interval_group", "activity_analysis",
            "activity_intervals_metrics", "intervals_fitness_daily", "garmin_recovery_daily",
            "injury_report", "athlete_intensity_profile", "activity",
        ).forEach { jdbc.update("delete from $it") }
    }

    // ---- plumbing -------------------------------------------------------------------------------

    private fun rpc(method: String, params: String?): JsonNode {
        val body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"$method\"" +
            (params?.let { ",\"params\":$it" } ?: "") + "}"
        val result = mockMvc.perform(
            post("/mcp").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .header("MCP-Protocol-Version", "2025-06-18")
                .content(body),
        ).andReturn()
        val response = result.response.contentAsString
        assertThat(result.response.status).`as`(response).isEqualTo(200)
        return objectMapper.readTree(response)
    }

    private fun call(tool: String, arguments: String = "{}"): JsonNode =
        rpc("tools/call", "{\"name\":\"$tool\",\"arguments\":$arguments}").path("result")

    private fun structured(tool: String, arguments: String = "{}"): JsonNode {
        val result = call(tool, arguments)
        assertThat(result.path("isError").asBoolean()).`as`(result.toString()).isFalse()
        return result.path("structuredContent")
    }

    private fun failure(tool: String, arguments: String = "{}"): JsonNode {
        val result = call(tool, arguments)
        assertThat(result.path("isError").asBoolean()).`as`(result.toString()).isTrue()
        return result.path("structuredContent")
    }

    private fun athleteId(): Long = athleteService.getDefaultAthlete().id

    private fun run(date: LocalDate, externalId: String, durationSeconds: Int = 3600, avgHr: Int? = 160): Activity =
        activities.save(Activity(athleteId(), ExternalSource.GARMIN, externalId, ActivityType.RUN,
            date.atTime(7, 0).atZone(zone).toInstant(), durationSeconds, 10_000.0, avgHr, 180))

    private fun analysis(activityId: Long) {
        analysisStore.replace(ActivityAnalysis(
            activityId = activityId,
            analysisVersion = "RUNNING_ANALYSIS_V1",
            status = AnalysisStatus.COMPLETE,
            inputSampleCompleteness = SampleCompleteness.FULL,
            computedAt = Instant.now(clock),
            session = SessionMetrics(validSampleCount = 3600, analysisDurationSeconds = 3600.0,
                halves = HalfSplitMetrics(hrChangePercent = 0.3, speedChangePercent = -5.9, speedHrDecouplingPercent = 6.24)),
            zones = ZoneExposure(zonePercent = mapOf(4 to 6.29, 5 to 92.84), totalSeconds = 6722.0),
            threshold = ThresholdExposure(lthr90Seconds = 6657.0, lthr100Seconds = 4738.0),
            laps = LapMetrics(lapCount = 22, speedCvPercent = 4.9),
            runningDynamics = RunningDynamicsMetrics(
                lapsWithDynamicsCount = 22,
                avgStrideLengthCm = 107.5,
                strideLengthChangePercent = -4.33,
                avgGroundContactTimeMs = 246.4,
                groundContactTimeChangePercent = 3.27,
                avgRunningPowerWatts = 320.4,
                runningPowerChangePercent = -5.37,
            ),
            intervalGroups = emptyList(),
        ))
    }

    // ---- exposure and separation ---------------------------------------------------------------

    @Test
    fun `tools list has exactly the seven read tools, all readOnlyHint, and no write tool`() {
        val tools = rpc("tools/list", "{}").path("result").path("tools")

        val names = tools.map { it.path("name").asText() }
        assertThat(names).containsExactlyInAnyOrder(
            "get_recent_activities", "get_activity_analysis", "get_training_context",
            "get_recovery_context", "get_fitness_trend", "get_workout_recommendation",
            "get_athlete_profile",
        )
        assertThat(names).doesNotContain("publish_workout")
        tools.forEach { t ->
            assertThat(t.path("annotations").path("readOnlyHint").asBoolean())
                .`as`("readOnlyHint of ${t.path("name").asText()}").isTrue()
        }
    }

    @Test
    fun `a forced publish_workout call fails - the write adapter is not registered at all`() {
        val response = rpc("tools/call", "{\"name\":\"publish_workout\",\"arguments\":{\"date\":\"2026-10-13\"}}")

        // Unknown tool: the server answers with an error (either JSON-RPC error or isError result,
        // depending on the SDK's mapping) and the publish service is never touched (see cleanUp()).
        val refused = response.has("error") || response.path("result").path("isError").asBoolean()
        assertThat(refused).`as`(response.toString()).isTrue()
    }

    // ---- the seven tools ------------------------------------------------------------------------

    @Test
    fun `get_recent_activities lists seeded activities newest first with analysis and match flags`() {
        val a = run(today.minusDays(1), "ext-1")
        run(today.minusDays(3), "ext-2")
        analysis(a.id)

        val body = structured("get_recent_activities", "{\"days\":14,\"limit\":10}")

        assertThat(body.path("totalInWindow").asInt()).isEqualTo(2)
        val items = body.path("activities")
        assertThat(items.size()).isEqualTo(2)
        assertThat(items[0].path("date").asText()).isEqualTo(today.minusDays(1).toString())
        assertThat(items[0].path("analysisAvailable").asBoolean()).isTrue()
        assertThat(items[0].path("intervalsMatched").asBoolean()).isFalse()
        assertThat(items[1].path("analysisAvailable").asBoolean()).isFalse()
        // no identifier of any kind leaves the server
        assertThat(body.toString()).doesNotContain("ext-1", "ext-2", "\"id\"")
    }

    @Test
    fun `get_recent_activities validates its arguments strictly`() {
        assertThat(failure("get_recent_activities", "{\"days\":0}").path("code").asText())
            .isEqualTo("INVALID_ARGUMENT")
        assertThat(failure("get_recent_activities", "{\"days\":91}").path("code").asText())
            .isEqualTo("INVALID_ARGUMENT")
        assertThat(failure("get_recent_activities", "{\"bogus\":1}").path("code").asText())
            .isEqualTo("INVALID_ARGUMENT")
    }

    @Test
    fun `get_activity_analysis returns the stored analysis with running dynamics, never recomputed`() {
        val a = run(today.minusDays(1), "ext-race", durationSeconds = 6724)
        analysis(a.id)

        val body = structured("get_activity_analysis", "{\"date\":\"${today.minusDays(1)}\"}")

        assertThat(body.path("activity").path("activityType").asText()).isEqualTo("RUN")
        val analysis = body.path("analysis")
        assertThat(analysis.path("status").asText()).isEqualTo("COMPLETE")
        assertThat(analysis.path("session").path("halves").path("speedHrDecouplingPercent").asDouble()).isEqualTo(6.24)
        assertThat(analysis.path("heartRateZones").path("percentByZone").path("5").asDouble()).isEqualTo(92.84)
        assertThat(analysis.path("lactateThresholdExposureSeconds").path("lthr100Seconds").asDouble()).isEqualTo(4738.0)
        val dynamics = analysis.path("runningDynamics")
        assertThat(dynamics.path("avgStrideLengthCm").asDouble()).isEqualTo(107.5)
        assertThat(dynamics.path("strideLengthChangePercent").asDouble()).isEqualTo(-4.33)
        // a metric the fixture did not report is absent/null - never estimated (the response
        // convention is non_null serialization: omitted means unknown)
        val vo = dynamics.path("avgVerticalOscillationCm")
        assertThat(vo.isMissingNode || vo.isNull).isTrue()
    }

    @Test
    fun `get_activity_analysis distinguishes missing activity from missing analysis`() {
        assertThat(failure("get_activity_analysis", "{\"date\":\"2026-01-01\"}").path("code").asText())
            .isEqualTo("ACTIVITY_NOT_FOUND")

        run(today.minusDays(2), "ext-noanalysis")
        val body = failure("get_activity_analysis", "{\"date\":\"${today.minusDays(2)}\"}")
        assertThat(body.path("code").asText()).isEqualTo("ANALYSIS_NOT_FOUND")
        assertThat(body.path("message").asText()).contains("never triggers")
    }

    @Test
    fun `get_training_context returns the canonical V2 snapshot with its hash`() {
        run(today.minusDays(1), "ext-ctx")

        val body = structured("get_training_context", "{\"date\":\"$today\"}")

        assertThat(body.path("contextVersion").asText()).isEqualTo("V2")
        assertThat(body.path("sha256").asText()).hasSize(64)
        val context = body.path("context")
        assertThat(context.path("contextVersion").asText()).isEqualTo("V2")
        assertThat(context.path("dataCoverage").path("supportedActivityCount").asInt()).isEqualTo(1)
        assertThat(context.path("lactateThresholdProvenance").path("sourceTracked").asBoolean()).isFalse()
        // V2's own forbidden-field rules carry straight through
        assertThat(body.toString()).doesNotContain("latitude", "externalId", "candidateTrainingTypes")
    }

    @Test
    fun `get_recovery_context reports stored metrics with their age and baseline validity`() {
        val day = today
        (1L..7L).forEach {
            recoverySnapshots.upsert(day.minusDays(it),
                RecoveryDailyValues(50.0, null, null, null, null, 50, null, null, null, null, null, null))
        }
        recoverySnapshots.upsert(day,
            RecoveryDailyValues(42.0, null, null, null, null, null, null, null, null, null, null, null))

        val body = structured("get_recovery_context", "{\"date\":\"$day\"}")

        assertThat(body.path("anyMetricAvailable").asBoolean()).isTrue()
        val hrv = body.path("recovery").path("hrv").path("lastNightAvgMs")
        assertThat(hrv.path("current").asDouble()).isEqualTo(42.0)
        assertThat(hrv.path("ageDays").asInt()).isEqualTo(0)
        assertThat(hrv.path("baselineStatus").asText()).isEqualTo("AVAILABLE")

        recoveryRepository.deleteAll()
        val empty = structured("get_recovery_context", "{\"date\":\"$day\"}")
        assertThat(empty.path("anyMetricAvailable").asBoolean()).isFalse()
    }

    @Test
    fun `get_fitness_trend returns stored days only and bounds the range`() {
        intervalsStore.upsertFitnessDay(athleteId(),
            IntervalsWellnessDay(today.minusDays(1), 16.0, 28.1, 0.0, 0.0, 0.0, Instant.now()), Instant.now())
        intervalsStore.upsertFitnessDay(athleteId(),
            IntervalsWellnessDay(today, 16.4, 32.4, 0.0, 0.0, 0.0, Instant.now()), Instant.now())

        val body = structured("get_fitness_trend",
            "{\"oldestDate\":\"${today.minusDays(6)}\",\"newestDate\":\"$today\"}")

        assertThat(body.path("daysStored").asInt()).isEqualTo(2)
        assertThat(body.path("days")[0].path("derivedForm").asDouble()).isEqualTo(16.0 - 28.1)
        assertThat(body.path("terminology").asText()).contains("RunningAI-derived")

        assertThat(failure("get_fitness_trend",
            "{\"oldestDate\":\"2026-01-01\",\"newestDate\":\"2026-06-01\"}").path("code").asText())
            .isEqualTo("INVALID_ARGUMENT")
        assertThat(failure("get_fitness_trend", "{\"oldestDate\":\"2026-01-02\",\"newestDate\":\"2026-01-01\"}")
            .path("code").asText()).isEqualTo("INVALID_ARGUMENT")
        assertThat(failure("get_fitness_trend", "{}").path("code").asText()).isEqualTo("INVALID_ARGUMENT")
    }

    @Test
    fun `get_workout_recommendation is suppressed while the injury state blocks non-REST training`() {
        // no injury report at all -> blocked by design (Phase 7B-2)
        val blocked = structured("get_workout_recommendation", "{}")
        assertThat(blocked.path("injurySafety").path("nonRestTrainingAllowed").asBoolean()).isFalse()
        assertThat(blocked.path("injurySafety").path("reasonCode").asText()).isEqualTo("NO_INJURY_STATE_RECORDED")
        val rec = blocked.path("recommendation")
        assertThat(rec.isMissingNode || rec.isNull).isTrue()
        assertThat(blocked.path("suppressedReason").asText()).contains("REST only")
    }

    @Test
    fun `get_workout_recommendation returns the deterministic recommendation once the state is RESOLVED`() {
        InjuryTestFixtures.seedResolved(injuryReports, athleteService, clock)
        run(today.minusDays(1), "ext-rec")

        val body = structured("get_workout_recommendation", "{}")

        assertThat(body.path("injurySafety").path("nonRestTrainingAllowed").asBoolean()).isTrue()
        val rec = body.path("recommendation")
        assertThat(rec.path("intent").asText()).isIn("REST", "RECOVERY", "EASY", "LONG", "CROSS_TRAINING")
        assertThat(rec.path("intent").asText()).isNotEqualTo("QUALITY")
        assertThat(rec.path("summary").asText()).isNotBlank()
        assertThat(body.path("note").asText()).contains("not an AI-coach prescription")
    }

    @Test
    fun `get_athlete_profile reports values with the source-untracked provenance warning`() {
        val empty = structured("get_athlete_profile", "{}")
        assertThat(empty.path("initialized").asBoolean()).isFalse()
        assertThat(empty.path("provenance").path("sourceTracked").asBoolean()).isFalse()
        val warning = empty.path("provenance").path("warning")
        assertThat(warning.isMissingNode || warning.isNull).isTrue()

        profileService.replaceDefaultProfile(AthleteIntensityProfileRequest(176, 290))
        val body = structured("get_athlete_profile", "{}")
        assertThat(body.path("lactateThresholdHeartRateBpm").asInt()).isEqualTo(176)
        assertThat(body.path("lactateThresholdPaceSecondsPerKm").asInt()).isEqualTo(290)
        assertThat(body.path("provenance").path("warning").asText()).contains("not tracked")
    }

    // ---- nothing changes ------------------------------------------------------------------------

    @Test
    fun `a full sweep of every read tool changes no database row`() {
        InjuryTestFixtures.seedResolved(injuryReports, athleteService, clock)
        val a = run(today.minusDays(1), "ext-sweep")
        analysis(a.id)
        recoverySnapshots.upsert(today, RecoveryDailyValues(42.0, null, null, null, null, null, null, null, null, null, null, null))
        intervalsStore.upsertFitnessDay(athleteId(), IntervalsWellnessDay(today, 16.0, 28.0, 0.0, 0.0, 0.0, Instant.now()), Instant.now())
        profileService.replaceDefaultProfile(AthleteIntensityProfileRequest(176, 290))

        val tables = listOf("activity", "activity_analysis", "garmin_recovery_daily",
            "intervals_fitness_daily", "injury_report", "athlete_intensity_profile", "workout_draft",
            "garmin_sync_state", "activity_sample", "activity_raw")
        fun counts() = tables.associateWith { jdbc.queryForObject("select count(*) from $it", Long::class.java) }
        val before = counts()

        structured("get_recent_activities")
        structured("get_activity_analysis", "{\"date\":\"${today.minusDays(1)}\"}")
        structured("get_training_context", "{\"date\":\"$today\"}")
        structured("get_recovery_context", "{\"date\":\"$today\"}")
        structured("get_fitness_trend", "{\"oldestDate\":\"${today.minusDays(6)}\",\"newestDate\":\"$today\"}")
        structured("get_workout_recommendation")
        structured("get_athlete_profile")

        assertThat(counts()).isEqualTo(before)
    }
}
