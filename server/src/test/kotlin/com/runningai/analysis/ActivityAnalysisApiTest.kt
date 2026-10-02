package com.runningai.analysis

import com.runningai.activity.Activity
import com.runningai.activity.ActivityRepository
import com.runningai.activity.ActivityType
import com.runningai.activity.ExternalSource
import com.runningai.activity.detail.ActivityDetailStore
import com.runningai.activity.detail.DetailPartRecord
import com.runningai.activity.detail.DetailPartStatus
import com.runningai.activity.detail.DetailPayloadType
import com.runningai.activity.detail.LapData
import com.runningai.activity.detail.SampleCompleteness
import com.runningai.activity.detail.SampleData
import com.runningai.activity.detail.SampleStreamFidelity
import com.runningai.activity.detail.ZoneData
import com.runningai.activity.detail.ZoneType
import com.runningai.athlete.AthleteIntensityProfile
import com.runningai.athlete.AthleteIntensityProfileRepository
import com.runningai.athlete.AthleteService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

/**
 * The analysis end to end over the real schema (H2 + Flyway), real calculators and real stores.
 * Nothing here can reach Garmin or Intervals: the analysis reads stored rows only.
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
class ActivityAnalysisApiTest {

    @Autowired private lateinit var service: RunningActivityAnalysisService
    @Autowired private lateinit var activities: ActivityRepository
    @Autowired private lateinit var detail: ActivityDetailStore
    @Autowired private lateinit var profiles: AthleteIntensityProfileRepository
    @Autowired private lateinit var athletes: AthleteService
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var mockMvc: MockMvc

    private val start: Instant = Instant.parse("2026-09-28T21:30:00Z")
    private var activityId: Long = 0

    @BeforeEach
    fun setUp() {
        activityId = activities.save(
            Activity(athletes.getDefaultAthlete().id, ExternalSource.GARMIN, "analysis-1", ActivityType.RUN, start, 360, 1200.0, 160, 180),
        ).id
    }

    @AfterEach
    fun cleanUp() {
        listOf(
            "activity_analysis_interval", "activity_analysis_interval_group", "activity_analysis",
            "activity_detail_collection", "activity_sample", "activity_zone", "activity_lap",
            "activity_detail", "activity_raw_payload", "activity_raw", "activity",
        ).forEach { jdbc.update("delete from $it") }
        profiles.deleteAll()
    }

    /** 37 samples at 10 s intervals: heart rate climbs, speed fades. */
    private fun storeSamples(count: Int = 37) {
        detail.replaceSamples(
            activityId,
            (0 until count).map { i ->
                val t = i * 10.0
                SampleData(
                    sampleIndex = i, sampleTime = start.plusSeconds(t.toLong()), elapsedSeconds = t,
                    distance = t * 3.0, speed = if (t < 180.0) 3.2 else 3.0,
                    heartRate = if (t < 180.0) 150.0 else 162.0, cadence = 180.0,
                )
            },
        )
    }

    private fun storeIntervalLaps() {
        detail.replaceLaps(
            activityId,
            listOf(
                LapData(1, start, 60.0, intensityType = "WARMUP", workoutStepIndex = 0, averageSpeed = 2.5, averageHeartRateBpm = 130.0),
                LapData(2, start.plusSeconds(60), 60.0, intensityType = "ACTIVE", workoutStepIndex = 1, averageSpeed = 5.0, averageHeartRateBpm = 170.0),
                LapData(3, start.plusSeconds(120), 60.0, intensityType = "RECOVERY", workoutStepIndex = 2, averageSpeed = 2.0, averageHeartRateBpm = 140.0),
                LapData(4, start.plusSeconds(180), 60.0, intensityType = "ACTIVE", workoutStepIndex = 1, averageSpeed = 4.8, averageHeartRateBpm = 176.0),
                LapData(5, start.plusSeconds(240), 60.0, intensityType = "RECOVERY", workoutStepIndex = 2, averageSpeed = 2.0, averageHeartRateBpm = 138.0),
                LapData(6, start.plusSeconds(300), 60.0, intensityType = "COOLDOWN", workoutStepIndex = 3, averageSpeed = 2.4, averageHeartRateBpm = 125.0),
            ),
        )
    }

    private fun storeZones() {
        detail.replaceZones(
            activityId, ZoneType.HEART_RATE,
            (1..5).map { ZoneData(ZoneType.HEART_RATE, it, minValue = 90.0 + it * 15, durationSeconds = it * 20.0) },
        )
    }

    private fun recordCompleteness(completeness: SampleCompleteness) {
        detail.recordPart(
            activityId,
            DetailPartRecord(
                DetailPayloadType.ACTIVITY_DETAILS_STREAM, DetailPartStatus.NORMALIZED, null, 37, start,
                SampleStreamFidelity(completeness, 20000, 37, 37),
            ),
        )
    }

    @Test
    fun `a run with samples, laps and zones is analysed completely`() {
        storeSamples()
        storeIntervalLaps()
        storeZones()
        recordCompleteness(SampleCompleteness.FULL)
        profiles.save(AthleteIntensityProfile(athletes.getDefaultAthlete().id, 170, null))

        val result = service.analyse(activityId)

        assertThat(result.status).isEqualTo(AnalysisStatus.COMPLETE)
        assertThat(result.analysisVersion).isEqualTo("RUNNING_ANALYSIS_V1")
        assertThat(result.inputSampleCompleteness).isEqualTo(SampleCompleteness.FULL)
        // halves split at 180 s: 150 bpm then 162 bpm, 3.2 m/s then 3.0 m/s
        assertThat(result.session.halves.firstHalfAvgHr).isEqualTo(150.0)
        assertThat(result.session.halves.secondHalfAvgHr).isEqualTo(162.0)
        assertThat(result.session.halves.hrChangeBpm).isEqualTo(12.0)
        assertThat(result.session.halves.speedChangePercent).isNotNull()
        assertThat(result.session.halves.speedHrDecouplingPercent).isNotNull()
        // cadence is measured for a run
        assertThat(result.session.halves.firstHalfAvgCadence).isEqualTo(180.0)
        // zones: 20+40+60+80+100 = 300 s
        assertThat(result.zones.totalSeconds).isEqualTo(300.0)
        assertThat(result.zones.zonePercent[5]).isNotNull()
        // LTHR 170: the second half sits at 162, which is >= 90% (153) but below 95% (161.5)? 162 >= 161.5
        assertThat(result.threshold.lthr90Seconds).isNotNull()
        assertThat(result.laps.lapCount).isEqualTo(6)
        assertThat(result.intervalGroups).hasSize(1)
        assertThat(result.intervalGroups.single().workRepCount).isEqualTo(2)
    }

    @Test
    fun `an activity with nothing stored is INSUFFICIENT_DATA, which is a data state and not a verdict`() {
        val result = service.analyse(activityId)

        assertThat(result.status).isEqualTo(AnalysisStatus.INSUFFICIENT_DATA)
        assertThat(result.session.validSampleCount).isZero()
        assertThat(result.session.halves).isEqualTo(HalfSplitMetrics())
        assertThat(result.intervalGroups).isEmpty()
        assertThat(result.laps.lapCount).isZero()
    }

    @Test
    fun `laps and zones alone are still worth analysing`() {
        storeIntervalLaps()
        storeZones()

        val result = service.analyse(activityId)

        assertThat(result.status).isEqualTo(AnalysisStatus.PARTIAL)
        assertThat(result.laps.lapCount).isEqualTo(6)
        assertThat(result.zones.totalSeconds).isEqualTo(300.0)
        assertThat(result.intervalGroups).hasSize(1)
        assertThat(result.session.validSampleCount).isZero()
    }

    @Test
    fun `without an LTHR on file threshold exposure stays null`() {
        storeSamples()

        val result = service.analyse(activityId)

        assertThat(result.threshold).isEqualTo(ThresholdExposure())
    }

    @Test
    fun `analysing twice replaces the derived rows instead of adding more`() {
        storeSamples()
        storeIntervalLaps()
        storeZones()
        service.analyse(activityId)
        val counts = derivedCounts()

        service.analyse(activityId)

        assertThat(derivedCounts()).isEqualTo(counts)
        assertThat(counts).containsEntry("activity_analysis", 1)
            .containsEntry("activity_analysis_interval_group", 1)
            .containsEntry("activity_analysis_interval", 2)
    }

    @Test
    fun `a session that loses its interval structure does not keep the old one`() {
        storeSamples()
        storeIntervalLaps()
        service.analyse(activityId)
        assertThat(service.find(activityId)!!.intervalGroups).hasSize(1)

        // re-collected without workout structure (e.g. the laps came back unstructured)
        detail.replaceLaps(activityId, listOf(LapData(1, start, 360.0, averageSpeed = 3.0, averageHeartRateBpm = 155.0)))
        service.analyse(activityId)

        assertThat(service.find(activityId)!!.intervalGroups).isEmpty()
        assertThat(derivedCounts()).containsEntry("activity_analysis_interval", 0)
            .containsEntry("activity_analysis_interval_group", 0)
    }

    @Test
    fun `the analysis never writes to the data it reads`() {
        storeSamples()
        storeIntervalLaps()
        storeZones()
        val before = sourceCounts()

        service.analyse(activityId)
        service.analyse(activityId)

        assertThat(sourceCounts()).isEqualTo(before)
    }

    @Test
    fun `an indoor ride is analysed without running cadence being invented for it`() {
        val rideId = activities.save(
            Activity(athletes.getDefaultAthlete().id, ExternalSource.GARMIN, "analysis-ride", ActivityType.INDOOR_CYCLING, start, 360, 0.0, 110, 130),
        ).id
        detail.replaceSamples(
            rideId,
            (0 until 10).map { i ->
                SampleData(sampleIndex = i, elapsedSeconds = i * 10.0, heartRate = 110.0, cadence = 85.0)
            },
        )

        val result = service.analyse(rideId)

        assertThat(result.session.halves.firstHalfAvgHr).isEqualTo(110.0)
        assertThat(result.session.halves.firstHalfAvgCadence).isNull()
        assertThat(result.session.halves.cadenceChangeSpm).isNull()
        jdbc.update("delete from activity_analysis where activity_id = ?", rideId)
        jdbc.update("delete from activity_sample where activity_id = ?", rideId)
        jdbc.update("delete from activity where id = ?", rideId)
    }

    // ---- HTTP -------------------------------------------------------------------------------------------

    @Test
    fun `POST computes and GET reads back the same analysis`() {
        storeSamples()
        storeIntervalLaps()
        storeZones()

        mockMvc.perform(post("/api/v1/activities/$activityId/analysis"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.analysisVersion").value("RUNNING_ANALYSIS_V1"))
            .andExpect(jsonPath("$.status").value("COMPLETE"))
            .andExpect(jsonPath("$.session.halves.hrChangeBpm").value(12.0))
            .andExpect(jsonPath("$.intervalGroups.length()").value(1))
            .andExpect(jsonPath("$.intervalGroups[0].workRepCount").value(2))

        mockMvc.perform(get("/api/v1/activities/$activityId/analysis"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.session.halves.hrChangeBpm").value(12.0))
            .andExpect(jsonPath("$.intervalGroups[0].repetitions.length()").value(2))
    }

    @Test
    fun `an unknown activity is refused`() {
        mockMvc.perform(post("/api/v1/activities/999999/analysis"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("ACTIVITY_NOT_FOUND"))
    }

    @Test
    fun `reading an analysis that was never computed says so`() {
        mockMvc.perform(get("/api/v1/activities/$activityId/analysis"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("ACTIVITY_ANALYSIS_NOT_FOUND"))
    }

    private fun derivedCounts() =
        listOf("activity_analysis", "activity_analysis_interval_group", "activity_analysis_interval")
            .associateWith { jdbc.queryForObject("select count(*) from $it", Int::class.java) }

    private fun sourceCounts() =
        listOf("activity", "activity_lap", "activity_zone", "activity_sample")
            .associateWith { jdbc.queryForObject("select count(*) from $it", Int::class.java) }
}
