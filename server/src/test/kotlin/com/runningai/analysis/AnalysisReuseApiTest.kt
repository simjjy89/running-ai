package com.runningai.analysis

import com.runningai.activity.Activity
import com.runningai.activity.ActivityRepository
import com.runningai.activity.ActivityType
import com.runningai.activity.ExternalSource
import com.runningai.activity.detail.ActivityDetailStore
import com.runningai.activity.detail.DetailPartRecord
import com.runningai.activity.detail.DetailPartStatus
import com.runningai.activity.detail.DetailPayloadType
import com.runningai.activity.detail.SampleCompleteness
import com.runningai.activity.detail.SampleData
import com.runningai.activity.detail.SampleStreamFidelity
import com.runningai.athlete.AthleteService
import org.assertj.core.api.Assertions.assertThat
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
import java.time.Instant

/**
 * The re-analysis policy against the real schema (H2 + Flyway V24), reproducing the Phase 7E-0 defect
 * and proving it is now repaired automatically.
 *
 * The setup is the production situation exactly: an `activity_analysis` row that V24 marked
 * `analysis_engine_revision = 0` with no input digest, which under the old string comparison was
 * indistinguishable from a current one.
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
class AnalysisReuseApiTest {

    /** The real calculator with a one-shot failure switch, so a mid-analysis failure can be observed. */
    class ScriptedSessionMetricsCalculator : SessionMetricsCalculator() {
        var failNext: Boolean = false

        override fun calculate(samples: List<SampleData>, includeCadence: Boolean): SessionMetrics {
            if (failNext) {
                failNext = false
                error("scripted calculator failure")
            }
            return super.calculate(samples, includeCadence)
        }
    }

    @TestConfiguration
    class Fakes {
        @Bean @Primary fun scriptedSessionMetrics() = ScriptedSessionMetricsCalculator()
    }

    @Autowired private lateinit var service: RunningActivityAnalysisService
    @Autowired private lateinit var sessionMetrics: ScriptedSessionMetricsCalculator
    @Autowired private lateinit var activities: ActivityRepository
    @Autowired private lateinit var detail: ActivityDetailStore
    @Autowired private lateinit var athletes: AthleteService
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var mockMvc: MockMvc

    private val start: Instant = Instant.parse("2026-09-28T21:30:00Z")
    private var activityId: Long = 0

    @BeforeEach
    fun setUp() {
        activityId = activities.save(
            Activity(athletes.getDefaultAthlete().id, ExternalSource.GARMIN, "reuse-1", ActivityType.RUN, start, 360, 1200.0, 160, 180),
        ).id
        detail.replaceSamples(
            activityId,
            (0 until 37).map { i ->
                val t = i * 10.0
                SampleData(
                    sampleIndex = i, sampleTime = start.plusSeconds(t.toLong()), elapsedSeconds = t,
                    distance = t * 3.0, speed = if (t < 180.0) 3.2 else 3.0,
                    heartRate = if (t < 180.0) 150.0 else 162.0, cadence = 180.0,
                )
            },
        )
        detail.recordPart(
            activityId,
            DetailPartRecord(
                DetailPayloadType.ACTIVITY_DETAILS_STREAM, DetailPartStatus.NORMALIZED, null, 37, start,
                SampleStreamFidelity(SampleCompleteness.FULL, 20000, 37, 37),
            ),
        )
    }

    @AfterEach
    fun cleanUp() {
        listOf(
            "activity_analysis_interval", "activity_analysis_interval_group", "activity_analysis",
            "activity_detail_collection", "activity_sample", "activity_zone", "activity_lap",
            "activity_detail", "activity_raw_payload", "activity_raw", "activity",
        ).forEach { jdbc.update("delete from $it") }
    }

    /** Exactly what V24 leaves behind for an analysis produced before revisions were tracked. */
    private fun ageTheStoredAnalysis() {
        jdbc.update("update activity_analysis set analysis_engine_revision = 0, analysis_input_digest = null")
    }

    @Test
    fun `a fresh analysis records the current contract, the current engine revision and its inputs`() {
        val analysis = service.analyse(activityId)

        assertThat(analysis.analysisVersion).isEqualTo(RUNNING_ANALYSIS_VERSION)
        assertThat(analysis.analysisEngineRevision).isEqualTo(RUNNING_ANALYSIS_ENGINE_REVISION)
        assertThat(analysis.inputDigest).isNotNull()
        assertThat(AnalysisReusePolicy.isCurrent(analysis)).isTrue()
    }

    @Test
    fun `an unchanged current analysis is reused rather than recomputed`() {
        val first = service.analyse(activityId)

        val outcome = service.analyseIfNeeded(activityId)

        assertThat(outcome.reused).isTrue()
        assertThat(outcome.reanalysisReason).isNull()
        assertThat(outcome.analysis.inputDigest).isEqualTo(first.inputDigest)
        // the row that comes back is the stored one, not a recomputation
        assertThat(outcome.analysis.computedAt).isEqualTo(service.find(activityId)!!.computedAt)
    }

    @Test
    fun `a pre-revision analysis is recomputed even though its version string matches`() {
        service.analyse(activityId)
        ageTheStoredAnalysis()

        // the string alone still says RUNNING_ANALYSIS_V1 - which is why the string alone was not enough
        assertThat(jdbc.queryForObject("select analysis_version from activity_analysis", String::class.java))
            .isEqualTo(RUNNING_ANALYSIS_VERSION)

        val outcome = service.analyseIfNeeded(activityId)

        assertThat(outcome.reused).isFalse()
        assertThat(outcome.reanalysisReason).isEqualTo(ReanalysisReason.ENGINE_REVISION_CHANGED)
        assertThat(outcome.analysis.analysisEngineRevision).isEqualTo(RUNNING_ANALYSIS_ENGINE_REVISION)
    }

    @Test
    fun `re-collected data is recomputed even when the engine did not change`() {
        service.analyse(activityId)

        // the stream was re-collected and now has more points
        detail.replaceSamples(
            activityId,
            (0 until 60).map { i ->
                SampleData(sampleIndex = i, sampleTime = start.plusSeconds(i * 6L), elapsedSeconds = i * 6.0,
                    speed = 3.1, heartRate = 155.0)
            },
        )
        detail.recordPart(
            activityId,
            DetailPartRecord(
                DetailPayloadType.ACTIVITY_DETAILS_STREAM, DetailPartStatus.NORMALIZED, null, 60, start,
                SampleStreamFidelity(SampleCompleteness.FULL, 20000, 60, 60),
            ),
        )

        val outcome = service.analyseIfNeeded(activityId)

        assertThat(outcome.reused).isFalse()
        assertThat(outcome.reanalysisReason).isEqualTo(ReanalysisReason.INPUT_CHANGED)
        assertThat(outcome.analysis.session.validSampleCount).isEqualTo(60)
    }

    @Test
    fun `re-analysis of unchanged data is deterministic`() {
        val first = service.analyse(activityId)

        val second = service.analyse(activityId)

        assertThat(second.session.halves.speedHrDecouplingPercent)
            .isEqualTo(first.session.halves.speedHrDecouplingPercent)
        assertThat(second.session.halves.firstHalfAvgHr).isEqualTo(first.session.halves.firstHalfAvgHr)
        assertThat(second.status).isEqualTo(first.status)
        assertThat(second.inputDigest).isEqualTo(first.inputDigest)
    }

    @Test
    fun `a failed re-analysis leaves the previously stored result intact`() {
        val first = service.analyse(activityId)

        // a calculator blows up part way through the next run
        sessionMetrics.failNext = true
        val failure = runCatching { service.analyse(activityId) }

        assertThat(failure.isFailure).isTrue()
        // nothing was deleted in preparation: the stored analysis is still the one that succeeded
        val stored = service.find(activityId)!!
        assertThat(stored.session.validSampleCount).isEqualTo(first.session.validSampleCount)
        assertThat(stored.inputDigest).isEqualTo(first.inputDigest)
        assertThat(stored.session.halves.speedHrDecouplingPercent)
            .isEqualTo(first.session.halves.speedHrDecouplingPercent)
    }

    @Test
    fun `the sweep reports a failed activity and keeps its previous analysis`() {
        service.analyse(activityId)
        ageTheStoredAnalysis()
        sessionMetrics.failNext = true

        mockMvc.perform(post("/api/v1/activities/analysis-refresh"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.failed").value(1))
            .andExpect(jsonPath("$.reanalysed").value(0))
            .andExpect(jsonPath("$.activities[0].failure").value("IllegalStateException"))

        assertThat(jdbc.queryForObject("select count(*) from activity_analysis", Int::class.java)).isEqualTo(1)
    }

    @Test
    fun `the sweep reports every stale analysis and repairs it, and says so before it does`() {
        service.analyse(activityId)
        ageTheStoredAnalysis()

        mockMvc.perform(post("/api/v1/activities/analysis-refresh").param("dryRun", "true"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.examined").value(1))
            .andExpect(jsonPath("$.reused").value(0))
            .andExpect(jsonPath("$.pending").value(1))
            .andExpect(jsonPath("$.reanalysed").value(0))
            .andExpect(jsonPath("$.activities[0].reanalysisReason").value("ENGINE_REVISION_CHANGED"))

        // a dry run changed nothing
        assertThat(jdbc.queryForObject("select analysis_engine_revision from activity_analysis", Int::class.java))
            .isZero()

        mockMvc.perform(post("/api/v1/activities/analysis-refresh"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.reanalysed").value(1))
            .andExpect(jsonPath("$.failed").value(0))

        assertThat(jdbc.queryForObject("select analysis_engine_revision from activity_analysis", Int::class.java))
            .isEqualTo(RUNNING_ANALYSIS_ENGINE_REVISION)

        // and the next sweep has nothing left to do
        mockMvc.perform(post("/api/v1/activities/analysis-refresh"))
            .andExpect(jsonPath("$.reused").value(1))
            .andExpect(jsonPath("$.reanalysed").value(0))
    }

    @Test
    fun `the stored engine revision is exposed on the analysis API`() {
        service.analyse(activityId)

        mockMvc.perform(post("/api/v1/activities/$activityId/analysis"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.analysisVersion").value(RUNNING_ANALYSIS_VERSION))
            .andExpect(jsonPath("$.analysisEngineRevision").value(RUNNING_ANALYSIS_ENGINE_REVISION))
    }
}
