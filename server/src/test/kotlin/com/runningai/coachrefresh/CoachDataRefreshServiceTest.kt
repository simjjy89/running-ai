package com.runningai.coachrefresh

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.runningai.activity.ActivityRepository
import com.runningai.activity.ActivityType
import com.runningai.activity.detail.ActivityDetailStore
import com.runningai.analysis.ActivityAnalysisStore
import com.runningai.athlete.AthleteService
import com.runningai.integration.garmin.GarminActivityDetailSource
import com.runningai.integration.garmin.GarminActivityPart
import com.runningai.integration.garmin.GarminActivitySource
import com.runningai.integration.garmin.GarminConnectorException
import com.runningai.integration.garmin.GarminRecoveryClient
import com.runningai.integration.intervals.IntervalsRateLimit
import com.runningai.integration.intervals.IntervalsReadClient
import com.runningai.integration.intervals.IntervalsReadResponse
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
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

private val json = ObjectMapper()

/**
 * [CoachDataRefreshService] end to end over the real schema, real sub-services (Garmin summary
 * sync, detail ingestion, running analysis, Intervals activity/fitness enrichment, Garmin recovery
 * sync), with only the four transport boundaries replaced by scripted fakes:
 * [GarminActivitySource] (summary), [GarminActivityDetailSource] (per-activity detail),
 * [IntervalsReadClient] (GET-only) and [GarminRecoveryClient]. No test here can reach a connector,
 * Garmin or Intervals.icu.
 */
@SpringBootTest(
    properties = [
        "running-ai.garmin.connector.base-url=http://127.0.0.1:9",
        "running-ai.intervals.api-key=",
        "running-ai.intervals.base-url=http://127.0.0.1:9",
        "running-ai.default-athlete.timezone=Asia/Seoul",
    ],
)
@ActiveProfiles("test")
class CoachDataRefreshServiceTest {

    class FakeActivitySource : GarminActivitySource {
        var items: List<JsonNode> = emptyList()
        var failWith: GarminConnectorException? = null
        var calls = 0
        /** When set, the first call counts down [onEntry] and waits on [releaseGate] before returning. */
        var onEntry: CountDownLatch? = null
        var releaseGate: CountDownLatch? = null
        override fun fetchActivities(start: Int, limit: Int): List<JsonNode> {
            calls++
            onEntry?.countDown()
            releaseGate?.await(5, TimeUnit.SECONDS)
            failWith?.let { throw it }
            return if (start == 0) items else emptyList()
        }
    }

    class FakeDetailSource : GarminActivityDetailSource {
        val responses = mutableMapOf<GarminActivityPart, () -> JsonNode>()
        val calls = mutableListOf<GarminActivityPart>()
        override fun fetch(part: GarminActivityPart, garminActivityId: String): JsonNode {
            calls += part
            return (responses[part] ?: error("no scripted response for $part")).invoke()
        }
    }

    class FakeIntervalsReadClient : IntervalsReadClient {
        var activitiesResponse: () -> JsonNode = { json.createArrayNode() }
        var wellnessResponse: () -> JsonNode = { json.createArrayNode() }
        var failWith: RuntimeException? = null
        var activityCalls = 0
        var wellnessCalls = 0
        override fun listActivities(oldest: LocalDate, newest: LocalDate): IntervalsReadResponse {
            activityCalls++
            failWith?.let { throw it }
            return IntervalsReadResponse(activitiesResponse(), IntervalsRateLimit(null, null, null))
        }
        override fun getActivity(intervalsActivityId: String): IntervalsReadResponse = error("not scripted")
        override fun getActivity(intervalsActivityId: String, includeIntervals: Boolean): IntervalsReadResponse = error("not scripted")
        override fun getWellness(oldest: LocalDate, newest: LocalDate): IntervalsReadResponse {
            wellnessCalls++
            failWith?.let { throw it }
            return IntervalsReadResponse(wellnessResponse(), IntervalsRateLimit(null, null, null))
        }
    }

    class FakeRecoveryClient : GarminRecoveryClient {
        var responses: MutableMap<LocalDate, () -> JsonNode> = mutableMapOf()
        var failWith: GarminConnectorException? = null
        var calls = mutableListOf<LocalDate>()
        override fun fetchDay(date: LocalDate): JsonNode {
            calls += date
            failWith?.let { throw it }
            return (responses[date] ?: { json.readTree("""{"date":"$date","metrics":{}}""") })()
        }
    }

    @TestConfiguration
    class FakeConfig {
        @Bean @Primary fun fakeActivitySource() = FakeActivitySource()
        @Bean @Primary fun fakeDetailSource() = FakeDetailSource()
        @Bean @Primary fun fakeIntervalsReadClient() = FakeIntervalsReadClient()
        @Bean @Primary fun fakeRecoveryClient() = FakeRecoveryClient()
    }

    @Autowired private lateinit var activitySource: FakeActivitySource
    @Autowired private lateinit var detailSource: FakeDetailSource
    @Autowired private lateinit var intervalsClient: FakeIntervalsReadClient
    @Autowired private lateinit var recoveryClient: FakeRecoveryClient
    @Autowired private lateinit var service: CoachDataRefreshService
    @Autowired private lateinit var evaluator: CoachDataFreshnessEvaluator
    @Autowired private lateinit var activities: ActivityRepository
    @Autowired private lateinit var detailStore: ActivityDetailStore
    @Autowired private lateinit var analysisStore: ActivityAnalysisStore
    @Autowired private lateinit var athleteService: AthleteService
    @Autowired private lateinit var jdbc: JdbcTemplate

    private val garminId = "188081596"
    private val date = LocalDate.of(2026, 9, 29) // the fixture activity's athlete-local date

    private fun fixture(path: String): JsonNode =
        json.readTree(javaClass.getResourceAsStream("/fixtures/$path") ?: error("missing fixture $path"))

    private fun scriptOneRunningActivity() {
        activitySource.items = listOf(fixture("garmin/running-activity.json"))
        detailSource.responses[GarminActivityPart.DETAIL] = { json.readTree("""{"activityId":188081596,"summaryDTO":{"distance":10000.0}}""") }
        detailSource.responses[GarminActivityPart.SPLITS] = { fixture("garmin/detail/splits.LIVE_SHAPE.ANONYMISED.json") }
        detailSource.responses[GarminActivityPart.HR_ZONES] = { fixture("garmin/detail/hr-zones.LIVE_SHAPE.ANONYMISED.json") }
        detailSource.responses[GarminActivityPart.POWER_ZONES] = { json.readTree("[]") }
        detailSource.responses[GarminActivityPart.SAMPLES] = { fixture("garmin/detail/samples-outdoor.LIVE_SHAPE.ANONYMISED.json") }
    }

    private fun matchedIntervalsActivity(): JsonNode {
        val arr = fixture("intervals/activities-list.LIVE_SHAPE.ANONYMISED.json") as ArrayNode
        (arr[0] as ObjectNode).put("external_id", garminId)
        return arr
    }

    private fun wellnessDay(d: LocalDate, ctl: Double, atl: Double) =
        json.readTree("""[{"id":"$d","ctl":$ctl,"atl":$atl,"rampRate":0.1,"ctlLoad":10.0,"atlLoad":10.0}]""")

    // The four fakes are Spring singleton beans (the @SpringBootTest context is cached and reused
    // across test methods), so their mutable state - call counts, scripted responses, failures -
    // would otherwise leak from one test into the next. Every test starts from a clean slate.
    @BeforeEach
    fun resetFakes() {
        activitySource.items = emptyList()
        activitySource.failWith = null
        activitySource.calls = 0
        activitySource.onEntry = null
        activitySource.releaseGate = null
        detailSource.responses.clear()
        detailSource.calls.clear()
        intervalsClient.activitiesResponse = { json.createArrayNode() }
        intervalsClient.wellnessResponse = { json.createArrayNode() }
        intervalsClient.failWith = null
        intervalsClient.activityCalls = 0
        intervalsClient.wellnessCalls = 0
        recoveryClient.responses.clear()
        recoveryClient.failWith = null
        recoveryClient.calls.clear()
    }

    @AfterEach
    fun cleanUp() {
        listOf(
            "activity_analysis_interval", "activity_analysis_interval_group", "activity_analysis",
            "activity_detail_collection", "activity_sample", "activity_zone", "activity_lap", "activity_detail",
            "activity_raw_payload", "intervals_raw_payload", "activity_intervals_metrics", "activity_source_link",
            "intervals_fitness_daily", "garmin_recovery_daily", "activity_raw", "garmin_sync_state", "activity",
        ).forEach { jdbc.update("delete from $it") }
    }

    @Test
    fun `Garmin summary sync failure stops the whole refresh - NOT_READY, nothing else attempted`() {
        activitySource.failWith = GarminConnectorException(GarminConnectorException.Reason.AUTH_REQUIRED, 401, "scripted")

        val result = service.refresh(date)

        assertThat(result.readyForCoach).isFalse()
        assertThat(result.reasons).contains("GARMIN_SUMMARY_SYNC_FAILED")
        assertThat(result.garmin.success).isFalse()
        assertThat(detailSource.calls).isEmpty()
        assertThat(intervalsClient.activityCalls).isZero()
        assertThat(intervalsClient.wellnessCalls).isZero()
        assertThat(recoveryClient.calls).isEmpty()
    }

    @Test
    fun `a new running activity is completed end to end and the refresh is READY`() {
        scriptOneRunningActivity()
        intervalsClient.activitiesResponse = { matchedIntervalsActivity() }
        intervalsClient.wellnessResponse = { wellnessDay(date, 45.0, 40.0) }
        recoveryClient.responses[date] = {
            json.readTree("""{"date":"$date","metrics":{"hrv":{"status":"OK","data":{"lastNightAvg":50.0,"weeklyAvg":48.0,"status":"BALANCED"}}}}""")
        }

        val result = service.refresh(date)

        assertThat(result.garmin.success).isTrue()
        assertThat(result.activities.recentActivityCount).isEqualTo(1)
        assertThat(result.activities.recentRunningActivityCount).isEqualTo(1)
        assertThat(result.activities.detailCollected).isEqualTo(1)
        assertThat(result.activities.analysisComputed).isEqualTo(1)
        // The live-shaped samples fixture is deliberately a DOWNSAMPLED example (3 stored of a
        // reported 6 native points) - this is itself a non-blocking warning case (section 13/34),
        // not a bug: fullSamples stays 0 and sampleIncomplete is the one that counts it.
        assertThat(result.activities.fullSamples).isZero()
        assertThat(result.activities.sampleIncomplete).isEqualTo(1)
        assertThat(result.activities.intervalsMatched).isEqualTo(1)
        assertThat(result.intervalsFitness.failed).isFalse()
        assertThat(result.intervalsFitness.daysStored).isEqualTo(1)
        assertThat(result.recovery.failed).isFalse()
        assertThat(result.freshness.newestActivityDate).isEqualTo(date)
        assertThat(result.freshness.fitnessAgeDays).isEqualTo(0)
        assertThat(result.readyForCoach).isTrue()
        assertThat(result.reasons).containsExactly("RECENT_ACTIVITY_SAMPLE_NOT_FULL")

        val activityId = activities.findByExternalSourceAndExternalId(com.runningai.activity.ExternalSource.GARMIN, garminId).get().id
        assertThat(analysisStore.find(activityId)).isNotNull()
    }

    @Test
    fun `idempotent second refresh - zero detail calls, zero analysis recompute`() {
        scriptOneRunningActivity()
        intervalsClient.activitiesResponse = { matchedIntervalsActivity() }
        intervalsClient.wellnessResponse = { wellnessDay(date, 45.0, 40.0) }
        service.refresh(date)

        detailSource.calls.clear()
        val activityId = activities.findByExternalSourceAndExternalId(com.runningai.activity.ExternalSource.GARMIN, garminId).get().id
        val analysisBefore = analysisStore.find(activityId)!!.computedAt

        val second = service.refresh(date)

        assertThat(detailSource.calls).isEmpty()
        assertThat(second.activities.detailCollected).isZero()
        assertThat(second.activities.detailComplete).isEqualTo(1)
        assertThat(second.activities.analysisComputed).isZero()
        assertThat(second.activities.analysisCurrent).isEqualTo(1)
        assertThat(analysisStore.find(activityId)!!.computedAt).isEqualTo(analysisBefore)
        assertThat(second.readyForCoach).isTrue()
    }

    @Test
    fun `a cycling activity is never pushed through running analysis`() {
        val cycling = (fixture("garmin/running-activity.json") as ObjectNode).deepCopy()
        cycling.replace("activityType", json.readTree("""{"typeId":10,"typeKey":"indoor_cycling","parentTypeId":10,"isHidden":false}"""))
        activitySource.items = listOf(cycling)
        detailSource.responses[GarminActivityPart.DETAIL] = { json.readTree("""{"activityId":188081596,"summaryDTO":{"distance":10000.0}}""") }
        detailSource.responses[GarminActivityPart.SPLITS] = { fixture("garmin/detail/splits.LIVE_SHAPE.ANONYMISED.json") }
        detailSource.responses[GarminActivityPart.HR_ZONES] = { fixture("garmin/detail/hr-zones.LIVE_SHAPE.ANONYMISED.json") }
        detailSource.responses[GarminActivityPart.POWER_ZONES] = { json.readTree("[]") }
        detailSource.responses[GarminActivityPart.SAMPLES] = { fixture("garmin/detail/samples-outdoor.LIVE_SHAPE.ANONYMISED.json") }
        intervalsClient.activitiesResponse = { json.createArrayNode() }
        intervalsClient.wellnessResponse = { wellnessDay(date, 45.0, 40.0) }

        val result = service.refresh(date)

        assertThat(result.activities.recentActivityCount).isEqualTo(1)
        assertThat(result.activities.recentRunningActivityCount).isZero()
        assertThat(result.activities.analysisComputed).isZero()
        assertThat(result.activities.analysisMissing).isZero()
        val activityId = activities.findByExternalSourceAndExternalId(com.runningai.activity.ExternalSource.GARMIN, garminId).get().id
        assertThat(analysisStore.find(activityId)).isNull()
        assertThat(result.readyForCoach).isTrue()
    }

    @Test
    fun `Intervals fitness refresh failure (401) makes the refresh NOT_READY with zero retry`() {
        scriptOneRunningActivity()
        intervalsClient.activitiesResponse = { matchedIntervalsActivity() }
        intervalsClient.failWith = com.runningai.integration.intervals.IntervalsException(
            com.runningai.integration.intervals.IntervalsException.Reason.AUTH_FAILED, 401, "scripted",
        )

        val result = service.refresh(date)

        assertThat(result.readyForCoach).isFalse()
        assertThat(result.reasons).contains("INTERVALS_FITNESS_REFRESH_FAILED")
        assertThat(intervalsClient.wellnessCalls).isEqualTo(1)
    }

    @Test
    fun `Garmin recovery transport failure makes the refresh NOT_READY`() {
        scriptOneRunningActivity()
        intervalsClient.activitiesResponse = { matchedIntervalsActivity() }
        intervalsClient.wellnessResponse = { wellnessDay(date, 45.0, 40.0) }
        recoveryClient.failWith = GarminConnectorException(GarminConnectorException.Reason.RATE_LIMITED, 429, "scripted")

        val result = service.refresh(date)

        assertThat(result.readyForCoach).isFalse()
        assertThat(result.reasons).contains("GARMIN_RECOVERY_SYNC_FAILED")
    }

    @Test
    fun `a real rest streak (zero recent activity, fresh successful sync) is not treated as stale`() {
        activitySource.items = emptyList()
        intervalsClient.wellnessResponse = { wellnessDay(date, 45.0, 40.0) }

        val result = service.refresh(date)

        assertThat(result.garmin.success).isTrue()
        assertThat(result.activities.recentActivityCount).isZero()
        assertThat(result.freshness.newestActivityDate).isNull()
        assertThat(result.reasons).contains("NO_RECENT_ACTIVITY") // a warning, not a blocker
        assertThat(result.readyForCoach).isTrue()
    }

    @Test
    fun `the DB-only evaluator reports stale fitness and recovery without any new network call`() {
        scriptOneRunningActivity()
        intervalsClient.activitiesResponse = { matchedIntervalsActivity() }
        intervalsClient.wellnessResponse = { wellnessDay(date, 45.0, 40.0) }
        recoveryClient.responses[date] = {
            json.readTree("""{"date":"$date","metrics":{"hrv":{"status":"OK","data":{"lastNightAvg":50.0,"weeklyAvg":48.0,"status":"BALANCED"}}}}""")
        }
        service.refresh(date)
        val activityId = activities.findByExternalSourceAndExternalId(com.runningai.activity.ExternalSource.GARMIN, garminId).get().id
        val readiness = listOf(evaluator.activityReadiness(activities.findById(activityId).get()))

        // Same DB, but judged against a date far enough ahead that the stored fitness/recovery rows
        // are now outside the freshness window - zero network calls made to reach this conclusion.
        val farFutureDate = date.plusDays(30)
        val evaluation = evaluator.evaluate(farFutureDate, athleteService.getDefaultAthlete().id, date, readiness)

        assertThat(evaluation.blockers).contains("FITNESS_DATA_TOO_OLD", "RECOVERY_DATA_TOO_OLD")
    }

    @Test
    fun `a concurrent refresh request is rejected with COACH_DATA_REFRESH_ALREADY_RUNNING, never queued`() {
        activitySource.items = emptyList()
        activitySource.onEntry = CountDownLatch(1)
        activitySource.releaseGate = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val firstCall = executor.submit<CoachDataRefreshResult> { service.refresh(date) }
            assertThat(activitySource.onEntry!!.await(5, TimeUnit.SECONDS))
                .withFailMessage("background refresh never reached the Garmin call").isTrue()

            assertThat(
                org.assertj.core.api.Assertions.catchThrowable { service.refresh(date) },
            ).isInstanceOf(CoachDataRefreshAlreadyRunningException::class.java)

            activitySource.releaseGate!!.countDown()
            val result = firstCall.get(5, TimeUnit.SECONDS)
            assertThat(result.garmin.success).isTrue()
        } finally {
            executor.shutdownNow()
        }
    }
}
