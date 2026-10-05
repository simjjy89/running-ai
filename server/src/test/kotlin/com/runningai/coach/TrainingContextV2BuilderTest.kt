package com.runningai.coach

import com.runningai.activity.Activity
import com.runningai.activity.ActivityRepository
import com.runningai.activity.ActivityType
import com.runningai.activity.ExternalSource
import com.runningai.activity.detail.ActivityDetailData
import com.runningai.activity.detail.ActivityDetailStore
import com.runningai.activity.detail.DetailPartRecord
import com.runningai.activity.detail.DetailPartStatus
import com.runningai.activity.detail.DetailPayloadType
import com.runningai.activity.detail.SampleCompleteness
import com.runningai.activity.detail.SampleStreamFidelity
import com.runningai.analysis.ActivityAnalysis
import com.runningai.analysis.ActivityAnalysisStore
import com.runningai.analysis.AnalysisStatus
import com.runningai.analysis.HalfSplitMetrics
import com.runningai.analysis.IntervalGroup
import com.runningai.analysis.IntervalRepetition
import com.runningai.analysis.LapMetrics
import com.runningai.analysis.RecoveryHrChange
import com.runningai.analysis.SessionMetrics
import com.runningai.analysis.ThresholdExposure
import com.runningai.analysis.ZoneExposure
import com.runningai.athlete.AthleteIntensityProfileRequest
import com.runningai.athlete.AthleteIntensityProfileService
import com.runningai.athlete.AthleteService
import com.runningai.enrichment.IntervalsActivitySnapshot
import com.runningai.enrichment.IntervalsEnrichmentStore
import com.runningai.enrichment.IntervalsWellnessDay
import com.runningai.recovery.RecoveryDailyValues
import com.runningai.recovery.RecoveryRepository
import com.runningai.recovery.RecoverySnapshotService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * [TrainingContextV2Builder] against the real schema. Every assertion is traced to exactly what was
 * stored: no Garmin call, no Intervals call and no analysis recompute happen anywhere in this class.
 */
@SpringBootTest(
    properties = [
        "running-ai.garmin.connector.base-url=http://127.0.0.1:9",
        "running-ai.intervals.api-key=",
        "running-ai.intervals.base-url=http://127.0.0.1:9",
        "running-ai.coach.context-v2.max-recent-activities=3",
        "running-ai.coach.context-v2.max-interval-groups-per-activity=2",
    ],
)
@ActiveProfiles("test")
class TrainingContextV2BuilderTest {

    @Autowired private lateinit var builder: TrainingContextV2Builder
    @Autowired private lateinit var activities: ActivityRepository
    @Autowired private lateinit var detailStore: ActivityDetailStore
    @Autowired private lateinit var analysisStore: ActivityAnalysisStore
    @Autowired private lateinit var intervalsStore: IntervalsEnrichmentStore
    @Autowired private lateinit var recoverySnapshots: RecoverySnapshotService
    @Autowired private lateinit var recoveryRepository: RecoveryRepository
    @Autowired private lateinit var athleteService: AthleteService
    @Autowired private lateinit var profileService: AthleteIntensityProfileService
    @Autowired private lateinit var jdbc: JdbcTemplate

    private val zone: ZoneId = ZoneId.of("Asia/Seoul")
    private val d: LocalDate = LocalDate.of(2026, 10, 3)
    private var athleteId: Long = 0

    @AfterEach
    fun cleanUp() {
        listOf(
            "activity_analysis_interval", "activity_analysis_interval_group", "activity_analysis",
            "activity_detail_collection", "activity_sample", "activity_zone", "activity_lap", "activity_detail",
            "activity_raw_payload", "activity_raw", "activity_intervals_metrics", "activity_source_link",
            "intervals_raw_payload", "intervals_fitness_daily", "garmin_recovery_daily", "activity",
            "athlete_intensity_profile",
        ).forEach { jdbc.update("delete from $it") }
    }

    private fun run(
        externalId: String,
        startedAt: Instant,
        type: ActivityType = ActivityType.RUN,
        durationSeconds: Int = 1800,
        distanceMeters: Double? = 6000.0,
        avgHr: Int? = 150,
        maxHr: Int? = 170,
    ): Activity {
        athleteId = athleteService.defaultAthlete.id
        return activities.save(Activity(athleteId, ExternalSource.GARMIN, externalId, type, startedAt, durationSeconds,
            distanceMeters, avgHr, maxHr))
    }

    private fun recordFullStream(activityId: Long, count: Int) {
        detailStore.recordPart(activityId, DetailPartRecord(
            DetailPayloadType.ACTIVITY_DETAILS_STREAM, DetailPartStatus.NORMALIZED, null, count, Instant.now(),
            SampleStreamFidelity(SampleCompleteness.FULL, 20000, count, count),
        ))
    }

    private fun recordDownsampledStream(activityId: Long, stored: Int, total: Int) {
        detailStore.recordPart(activityId, DetailPartRecord(
            DetailPayloadType.ACTIVITY_DETAILS_STREAM, DetailPartStatus.NORMALIZED, null, stored, Instant.now(),
            SampleStreamFidelity(SampleCompleteness.DOWNSAMPLED, 20000, stored, total),
        ))
    }

    private fun detail(activityId: Long, cadence: Double? = 170.0, power: Double? = 220.0) {
        detailStore.replaceDetail(activityId, ActivityDetailData(
            sourcePayloadType = DetailPayloadType.ACTIVITY_DETAIL,
            averageRunningCadenceSpm = cadence,
            averagePower = power,
        ))
    }

    private fun analysis(
        activityId: Long,
        status: AnalysisStatus = AnalysisStatus.COMPLETE,
        decoupling: Double? = 4.2,
        lthr90: Double? = 300.0,
        zonePercent: Map<Int, Double> = mapOf(1 to 10.0, 2 to 60.0, 3 to 30.0),
        groups: List<IntervalGroup> = emptyList(),
    ): ActivityAnalysis {
        val a = ActivityAnalysis(
            activityId = activityId,
            analysisVersion = "RUNNING_ANALYSIS_V1",
            status = status,
            inputSampleCompleteness = SampleCompleteness.FULL,
            computedAt = Instant.now(),
            session = SessionMetrics(
                validSampleCount = 100,
                halves = HalfSplitMetrics(hrChangePercent = 3.0, speedChangePercent = -2.0,
                    speedHrDecouplingPercent = decoupling, cadenceChangePercent = -1.0),
            ),
            zones = ZoneExposure(zonePercent = zonePercent, totalSeconds = 1800.0),
            threshold = ThresholdExposure(lthr90Seconds = lthr90, lthr95Seconds = 50.0, lthr100Seconds = 10.0),
            laps = LapMetrics(lapCount = 4, speedCvPercent = 2.5),
            intervalGroups = groups,
        )
        analysisStore.replace(a)
        return a
    }

    private fun intervalGroup(index: Int, workReps: Int = 4) = IntervalGroup(
        groupIndex = index,
        workoutStepIndex = index,
        repetitions = (1..workReps).map {
            IntervalRepetition(it, index, it, it, 180.0, 1000.0, 5.0, 165.0, 175.0, 180.0, null)
        },
        meanSpeed = 5.0, speedStdDev = 0.1, speedCvPercent = 2.0,
        firstRepSpeed = 5.1, lastRepSpeed = 4.9, lastVsFirstSpeedChangePercent = -3.9,
        firstRepHr = 160.0, lastRepHr = 172.0, hrProgressionBpm = 12.0,
        recovery = RecoveryHrChange(172.0, 140.0, 32.0, 60.0),
    )

    private fun intervalsMetrics(activityId: Long, intervalsId: String, start: Instant, elapsed: Int, distance: Double?) {
        intervalsStore.upsertMetrics(
            activityId,
            IntervalsActivitySnapshot(intervalsId, "GARMIN_CONNECT", null, "Run", start, elapsed, elapsed, distance,
                50, 80.0, 30.0, 25.0, Instant.now()),
            Instant.now(),
        )
    }

    private fun fitnessDay(date: LocalDate, ctl: Double?, atl: Double?) {
        athleteId = athleteService.defaultAthlete.id
        intervalsStore.upsertFitnessDay(athleteId, IntervalsWellnessDay(date, ctl, atl, 0.0, 0.0, 0.0, Instant.now()), Instant.now())
    }

    private fun recoveryDay(date: LocalDate, rhr: Int?) {
        recoverySnapshots.upsert(date, RecoveryDailyValues(null, null, null, null, null, rhr, null, null, null, null, null, null))
    }

    // ---- tests --------------------------------------------------------------------------------

    @Test
    fun `an empty history yields zero counts and null dates, never fabricated history`() {
        val ctx = builder.build(d, SessionConstraints())

        assertThat(ctx.dataCoverage.supportedActivityCount).isZero()
        assertThat(ctx.dataCoverage.oldestActivityDate).isNull()
        assertThat(ctx.dataCoverage.newestActivityDate).isNull()
        assertThat(ctx.recentActivities).isEmpty()
        assertThat(ctx.trainingLoad.sourceDate).isNull()
        assertThat(ctx.trainingRhythm.lastRunDate).isNull()
        assertThat(ctx.trainingRhythm.structuredIntervalDetectionAvailable).isTrue()
    }

    @Test
    fun `an activity exactly 90 days back is included, one day further back is not`() {
        val onBoundary = run("ext-90", d.minusDays(89).atStartOfDay(zone).toInstant())
        val beyond = run("ext-91", d.minusDays(91).atStartOfDay(zone).toInstant())

        val ctx = builder.build(d, SessionConstraints())

        assertThat(ctx.dataCoverage.supportedActivityCount).isEqualTo(1)
        assertThat(ctx.dataCoverage.oldestActivityDate).isEqualTo(onBoundary.startedAt.atZone(zone).toLocalDate())
        assertThat(activities.findById(beyond.id)).isPresent() // stored, just outside the window
    }

    @Test
    fun `an activity started after the session date never leaks into the context`() {
        run("ext-future", d.plusDays(1).atStartOfDay(zone).toInstant())
        run("ext-today", d.atTime(8, 0).atZone(zone).toInstant())

        val ctx = builder.build(d, SessionConstraints())

        assertThat(ctx.dataCoverage.supportedActivityCount).isEqualTo(1)
        assertThat(ctx.dataCoverage.newestActivityDate).isEqualTo(d)
    }

    @Test
    fun `an activity completed earlier the same day as the session date is included`() {
        run("ext-same-day", d.atTime(6, 0).atZone(zone).toInstant())

        val ctx = builder.build(d, SessionConstraints())

        assertThat(ctx.dataCoverage.supportedActivityCount).isEqualTo(1)
        assertThat(ctx.recentActivities.single().activity.date).isEqualTo(d)
    }

    @Test
    fun `fitness, recovery and matching never include a day after the session date`() {
        val activity = run("ext-today", d.atTime(7, 0).atZone(zone).toInstant())
        fitnessDay(d, ctl = 20.0, atl = 18.0)
        fitnessDay(d.plusDays(1), ctl = 99.0, atl = 99.0) // must never be read
        recoveryDay(d, rhr = 50)
        recoveryDay(d.plusDays(1), rhr = 999) // must never be counted
        intervalsMetrics(activity.id, "i-future-check", activity.startedAt, 1800, 6000.0)

        val ctx = builder.build(d, SessionConstraints())

        assertThat(ctx.trainingLoad.ctl).isEqualTo(20.0)
        assertThat(ctx.dataCoverage.recoveryDaysAvailable).isEqualTo(1)
    }

    @Test
    fun `recent activities are newest first and capped at the configured maximum`() {
        (1..5).forEach { i -> run("ext-$i", d.minusDays((5 - i).toLong()).atTime(8, 0).atZone(zone).toInstant()) }

        val ctx = builder.build(d, SessionConstraints())

        assertThat(ctx.dataCoverage.supportedActivityCount).isEqualTo(5) // coverage counts the whole window
        assertThat(ctx.recentActivities).hasSize(3) // max-recent-activities=3 in this test's properties
        assertThat(ctx.recentActivities.map { it.activity.date })
            .containsExactly(d, d.minusDays(1), d.minusDays(2))
    }

    @Test
    fun `FULL sample fidelity is carried through to the activity's data quality`() {
        val a = run("ext-full", d.atTime(8, 0).atZone(zone).toInstant())
        recordFullStream(a.id, 2000)
        analysis(a.id)

        val ctx = builder.build(d, SessionConstraints())

        val q = ctx.recentActivities.single().dataQuality
        assertThat(q.sampleCompleteness).isEqualTo(SampleCompleteness.FULL)
        assertThat(q.analysisStatus).isEqualTo(AnalysisStatus.COMPLETE)
        assertThat(q.analysisVersion).isEqualTo("RUNNING_ANALYSIS_V1")
        assertThat(ctx.dataCoverage.fullSampleActivityCount).isEqualTo(1)
    }

    @Test
    fun `a DOWNSAMPLED stream is reported, not silently treated as FULL`() {
        val a = run("ext-down", d.atTime(8, 0).atZone(zone).toInstant())
        recordDownsampledStream(a.id, 1000, 2000)

        val ctx = builder.build(d, SessionConstraints())

        assertThat(ctx.recentActivities.single().dataQuality.sampleCompleteness).isEqualTo(SampleCompleteness.DOWNSAMPLED)
        assertThat(ctx.dataCoverage.fullSampleActivityCount).isZero()
    }

    @Test
    fun `an activity with no stored analysis reports missing, never recomputed`() {
        val a = run("ext-no-analysis", d.atTime(8, 0).atZone(zone).toInstant())

        val ctx = builder.build(d, SessionConstraints())

        val ev = ctx.recentActivities.single()
        assertThat(ev.runningAiAnalysis).isNull()
        assertThat(ev.dataQuality.analysisStatus).isNull()
        assertThat(ev.dataQuality.analysisVersion).isNull()
        assertThat(ctx.dataCoverage.analysedActivityCount).isZero()
    }

    @Test
    fun `an activity with no Intervals match reports missing, never a fabricated zero`() {
        val a = run("ext-unmatched", d.atTime(8, 0).atZone(zone).toInstant())

        val ctx = builder.build(d, SessionConstraints())

        assertThat(ctx.recentActivities.single().intervals).isNull()
        assertThat(ctx.dataCoverage.intervalsMatchedActivityCount).isZero()
    }

    @Test
    fun `interval groups are carried through as summaries, capped, with no individual repetitions`() {
        val a = run("ext-intervals", d.atTime(8, 0).atZone(zone).toInstant(), type = ActivityType.RUN)
        recordFullStream(a.id, 2712)
        analysis(a.id, groups = listOf(intervalGroup(1), intervalGroup(2), intervalGroup(3)))

        val ctx = builder.build(d, SessionConstraints())

        val groups = ctx.recentActivities.single().runningAiAnalysis!!.intervalGroups
        assertThat(groups).hasSize(2) // max-interval-groups-per-activity=2 in this test's properties
        assertThat(groups.first().workRepCount).isEqualTo(4)
        assertThat(groups.first().recoveryHrDropBpm).isEqualTo(32.0)
        // no "repetitions" field anywhere reachable on the evidence type
        assertThat(IntervalGroupEvidence::class.java.declaredFields.map { it.name }).doesNotContain("repetitions")
    }

    @Test
    fun `Intervals per-activity evidence is reported separately from RunningAI analysis`() {
        val a = run("ext-both", d.atTime(8, 0).atZone(zone).toInstant())
        recordFullStream(a.id, 1800)
        analysis(a.id)
        intervalsMetrics(a.id, "i-both", a.startedAt, 1800, 6000.0)

        val ctx = builder.build(d, SessionConstraints())

        val ev = ctx.recentActivities.single()
        assertThat(ev.runningAiAnalysis).isNotNull()
        assertThat(ev.intervals).isNotNull()
        assertThat(ev.intervals!!.trainingLoad).isEqualTo(50)
        assertThat(ev.intervals!!.ctlAfterActivity).isEqualTo(30.0)
    }

    @Test
    fun `CTL and ATL report the exact stored source date and age, never today's placeholder`() {
        fitnessDay(d.minusDays(2), ctl = 15.0, atl = 12.0)

        val ctx = builder.build(d, SessionConstraints())

        assertThat(ctx.trainingLoad.sourceDate).isEqualTo(d.minusDays(2))
        assertThat(ctx.trainingLoad.ageDays).isEqualTo(2)
        assertThat(ctx.trainingLoad.ctl).isEqualTo(15.0)
        assertThat(ctx.trainingLoad.atl).isEqualTo(12.0)
        assertThat(ctx.trainingLoad.derivedForm).isEqualTo(3.0)
    }

    @Test
    fun `sourceFreshness re-packages newestActivityDate, fitness source date-age and the most recent recovery metric (Phase 6H-9)`() {
        fitnessDay(d.minusDays(1), ctl = 30.0, atl = 25.0)
        recoveryDay(d, rhr = 50)        // most recent: age 0
        recoveryDay(d.minusDays(3), rhr = 55) // older: must not win

        val ctx = builder.build(d, SessionConstraints())

        assertThat(ctx.sourceFreshness.newestActivityDate).isEqualTo(ctx.dataCoverage.newestActivityDate)
        assertThat(ctx.sourceFreshness.fitnessSourceDate).isEqualTo(d.minusDays(1))
        assertThat(ctx.sourceFreshness.fitnessAgeDays).isEqualTo(1)
        assertThat(ctx.sourceFreshness.recoverySourceDate).isEqualTo(d)
        assertThat(ctx.sourceFreshness.recoveryAgeDays).isEqualTo(0)
    }

    @Test
    fun `sourceFreshness is all null when nothing is stored - never fabricated`() {
        val ctx = builder.build(d, SessionConstraints())

        assertThat(ctx.sourceFreshness.newestActivityDate).isNull()
        assertThat(ctx.sourceFreshness.fitnessSourceDate).isNull()
        assertThat(ctx.sourceFreshness.fitnessAgeDays).isNull()
        assertThat(ctx.sourceFreshness.recoverySourceDate).isNull()
        assertThat(ctx.sourceFreshness.recoveryAgeDays).isNull()
    }

    @Test
    fun `D-7 and D-28 fitness snapshots are exact-day only, no nearest-day substitution`() {
        fitnessDay(d, ctl = 20.0, atl = 18.0)
        fitnessDay(d.minusDays(7), ctl = 17.0, atl = 15.0)
        // deliberately nothing at d-28, and something one day off (d-27) that must NOT be used

        fitnessDay(d.minusDays(27), ctl = 999.0, atl = 999.0)

        val ctx = builder.build(d, SessionConstraints())

        assertThat(ctx.trainingLoad.sevenDaysAgo).isEqualTo(FitnessSnapshotV2(d.minusDays(7), 17.0, 15.0, 2.0))
        assertThat(ctx.trainingLoad.twentyEightDaysAgo).isNull()
    }

    @Test
    fun `sparse history is visible as a small count against the full window, not reinterpreted`() {
        run("ext-sparse", d.minusDays(40).atTime(8, 0).atZone(zone).toInstant())

        val ctx = builder.build(d, SessionConstraints())

        assertThat(ctx.dataCoverage.historyWindowDays).isEqualTo(90)
        assertThat(ctx.dataCoverage.supportedActivityCount).isEqualTo(1)
    }

    @Test
    fun `low recovery coverage is reported as a count, nothing is invented for the missing days`() {
        recoveryDay(d, rhr = 50)
        recoveryDay(d.minusDays(10), rhr = 55)
        recoveryDay(d.minusDays(20), rhr = 52)

        val ctx = builder.build(d, SessionConstraints())

        assertThat(ctx.dataCoverage.recoveryWindowDays).isEqualTo(28)
        assertThat(ctx.dataCoverage.recoveryDaysAvailable).isEqualTo(3)
    }

    @Test
    fun `structured interval detection reflects an actual stored interval group, not a speed guess`() {
        val withIntervals = run("ext-struct", d.minusDays(1).atTime(8, 0).atZone(zone).toInstant())
        recordFullStream(withIntervals.id, 100)
        analysis(withIntervals.id, groups = listOf(intervalGroup(1)))
        run("ext-plain", d.atTime(8, 0).atZone(zone).toInstant())

        val ctx = builder.build(d, SessionConstraints())

        assertThat(ctx.trainingRhythm.lastStructuredIntervalDate).isEqualTo(d.minusDays(1))
        assertThat(ctx.trainingRhythm.daysSinceLastStructuredInterval).isEqualTo(1)
    }

    @Test
    fun `a long run is detected using the existing configured threshold, not a new one`() {
        run("ext-long", d.minusDays(2).atTime(8, 0).atZone(zone).toInstant(), durationSeconds = 95 * 60)
        run("ext-short", d.minusDays(1).atTime(8, 0).atZone(zone).toInstant(), durationSeconds = 30 * 60)

        val ctx = builder.build(d, SessionConstraints())

        assertThat(ctx.trainingRhythm.lastLongRunDate).isEqualTo(d.minusDays(2))
        assertThat(ctx.trainingRhythm.lastRunDate).isEqualTo(d.minusDays(1))
    }

    @Test
    fun `consecutive active and rest days are computed from actual daily presence`() {
        run("ext-a1", d.atTime(8, 0).atZone(zone).toInstant())
        run("ext-a2", d.minusDays(1).atTime(8, 0).atZone(zone).toInstant())
        // d-2 is a rest day (no activity)
        run("ext-a3", d.minusDays(3).atTime(8, 0).atZone(zone).toInstant())

        val ctx = builder.build(d, SessionConstraints())

        assertThat(ctx.trainingRhythm.consecutiveActiveDays).isEqualTo(2)
    }

    @Test
    fun `athlete thresholds are passed through unmodified, null stays null`() {
        val ctxNoProfile = builder.build(d, SessionConstraints())
        assertThat(ctxNoProfile.athlete.lactateThresholdHeartRateBpm).isNull()
        assertThat(ctxNoProfile.athlete.lactateThresholdPaceSecondsPerKm).isNull()

        profileService.replaceDefaultProfile(AthleteIntensityProfileRequest(172, 290))
        val ctxWithProfile = builder.build(d, SessionConstraints())
        assertThat(ctxWithProfile.athlete.lactateThresholdHeartRateBpm).isEqualTo(172)
        assertThat(ctxWithProfile.athlete.lactateThresholdPaceSecondsPerKm).isEqualTo(290)
    }

    @Test
    fun `constraints pass through unchanged`() {
        val constraints = SessionConstraints(availableMinutes = 40, userFeedback = "legs heavy")

        val ctx = builder.build(d, constraints)

        assertThat(ctx.constraints).isEqualTo(constraints)
    }

    @Test
    fun `V2 never includes a candidateTrainingTypes field anywhere in its type`() {
        val allFields = listOf(
            TrainingContextV2::class.java, DataCoverageV2::class.java, TrainingRhythmV2::class.java,
            TrainingLoadContextV2::class.java, RecentActivityEvidence::class.java,
        ).flatMap { it.declaredFields.map { f -> f.name } }

        assertThat(allFields).noneMatch { it.contains("candidate", ignoreCase = true) }
    }
}
