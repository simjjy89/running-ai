package com.runningai.integration.strava

import com.garmin.fit.Sport
import com.garmin.fit.SubSport
import com.runningai.activity.ActivityRepository
import com.runningai.activity.ActivityType
import com.runningai.activity.ExternalSource
import com.runningai.activity.detail.ActivityDetailStore
import com.runningai.activity.detail.DetailPartStatus
import com.runningai.activity.detail.DetailPayloadType
import com.runningai.activity.detail.SampleCompleteness
import com.runningai.activity.detail.SampleData
import com.runningai.analysis.RunningActivityAnalysisService
import com.runningai.athlete.AthleteService
import com.runningai.enrichment.StravaActivityImportRepository
import com.runningai.enrichment.StravaImportOutcome
import com.runningai.enrichment.StravaMatchMethod
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.util.ReflectionTestUtils
import java.nio.file.Path
import java.time.Instant

/**
 * The Strava archive importer end to end over the real schema (H2 + Flyway V26), the real decoder,
 * mappers, enricher, detail store and analysis engine. The only thing faked is the archive directory,
 * which is built from synthetic FIT files - no byte of the athlete's real export is committed.
 *
 * Not `@Transactional`: the importer's per-file records have to be observed as committed, which is what
 * makes a re-invocation a resume.
 */
@SpringBootTest(
    properties = [
        "running-ai.garmin.connector.base-url=http://127.0.0.1:9",
        "running-ai.intervals.base-url=http://127.0.0.1:9",
        "running-ai.intervals.api-key=",
        "running-ai.strava.archive-import.enabled=true",
    ],
)
@ActiveProfiles("test")
class StravaArchiveImportServiceTest {

    @Autowired private lateinit var service: StravaArchiveImportService
    @Autowired private lateinit var properties: StravaArchiveImportProperties
    @Autowired private lateinit var activities: ActivityRepository
    @Autowired private lateinit var detail: ActivityDetailStore
    @Autowired private lateinit var analysis: RunningActivityAnalysisService
    @Autowired private lateinit var athletes: AthleteService
    @Autowired private lateinit var imports: StravaActivityImportRepository
    @Autowired private lateinit var jdbc: JdbcTemplate

    private val start: Instant = Instant.parse("2026-08-10T00:36:56Z")

    @AfterEach
    fun cleanUp() {
        listOf(
            "activity_analysis_interval", "activity_analysis_interval_group", "activity_analysis",
            "strava_activity_import", "intervals_activity_import", "activity_detail_collection",
            "activity_sample", "activity_zone", "activity_lap", "activity_detail",
            "activity_raw_payload", "activity_raw", "activity_intervals_metrics",
            "activity_source_link", "activity", "intervals_raw_payload",
        ).forEach { jdbc.update("delete from $it") }
    }

    private fun useArchive(root: Path) {
        ReflectionTestUtils.setField(properties, "archivePath", root.toString())
    }

    private fun archiveWith(root: Path, vararg rows: SyntheticFitFiles.ArchiveRow) {
        SyntheticFitFiles.writeArchive(root, rows.toList())
        useArchive(root)
    }

    private fun runRow(
        tmp: Path,
        id: String,
        uuid: String,
        date: String = "Aug 10, 2026, 12:36:56 AM",
        startedAt: Instant = start,
        sport: Sport = Sport.RUNNING,
        subSport: SubSport? = null,
        label: String = "Run",
        lapDurations: List<Double> = listOf(6.0, 4.0),
        withHeartRate: Boolean = false,
    ) = SyntheticFitFiles.ArchiveRow(
        id, date, label, "upload-$id.fit.gz",
        SyntheticFitFiles.stravaReEncode(
            tmp, startedAt, uuid, lapDurations = lapDurations, sport = sport, subSport = subSport,
            withHeartRate = withHeartRate),
    )

    // ---- creating activities the archive alone has ----

    @Test
    fun `imports an activity no other source holds, keeping the Strava id verbatim`(@TempDir tmp: Path) {
        archiveWith(tmp, runRow(tmp, "19929243405", "UUID-1"))

        val result = service.importArchive(StravaArchiveImportRequest())

        assertThat(result.imported).isEqualTo(1)
        val activity = activities.findByExternalSourceAndExternalId(ExternalSource.STRAVA, "19929243405")
        assertThat(activity).isPresent
        assertThat(activity.get().activityType).isEqualTo(ActivityType.RUN)
        // No Garmin id is ever synthesised for a workout Garmin never saw.
        assertThat(activities.findByExternalSourceAndExternalId(ExternalSource.GARMIN, "19929243405")).isEmpty
    }

    @Test
    fun `an indoor ride is stored as INDOOR_CYCLING and never analysed as a run`(@TempDir tmp: Path) {
        archiveWith(tmp, runRow(tmp, "19929242917", "UUID-RIDE", label = "Ride",
            sport = Sport.CYCLING, subSport = SubSport.INDOOR_CYCLING))

        service.importArchive(StravaArchiveImportRequest())

        val activity = activities
            .findByExternalSourceAndExternalId(ExternalSource.STRAVA, "19929242917").orElseThrow()
        assertThat(activity.activityType).isEqualTo(ActivityType.INDOOR_CYCLING)

        // The engine's running-only metrics must stay empty for a bike: cadence change and running
        // dynamics are running measurements and a bike's cadence is a different thing with the same name.
        val stored = analysis.find(activity.id)
        assertThat(stored).isNotNull
        assertThat(stored!!.session.halves.firstHalfAvgCadence).isNull()
        assertThat(stored.session.halves.secondHalfAvgCadence).isNull()
        assertThat(stored.session.halves.cadenceChangeSpm).isNull()
        assertThat(stored.runningDynamics.avgStrideLengthCm).isNull()
        assertThat(stored.runningDynamics.lapsWithDynamicsCount).isZero()
    }

    @Test
    fun `a treadmill run is stored as TREADMILL_RUN`(@TempDir tmp: Path) {
        archiveWith(tmp, runRow(tmp, "19929243427", "UUID-TM", subSport = SubSport.TREADMILL))

        service.importArchive(StravaArchiveImportRequest())

        assertThat(activities.findByExternalSourceAndExternalId(ExternalSource.STRAVA, "19929243427")
            .orElseThrow().activityType).isEqualTo(ActivityType.TREADMILL_RUN)
    }

    @Test
    fun `a sport no ActivityType models is recorded and skipped, never approximated`(@TempDir tmp: Path) {
        archiveWith(tmp,
            runRow(tmp, "19929243500", "UUID-WT", label = "Weight Training", sport = Sport.TRAINING),
            runRow(tmp, "19929243521", "UUID-WALK", label = "Walk", sport = Sport.WALKING))

        val result = service.importArchive(StravaArchiveImportRequest())

        assertThat(result.skippedUnsupportedType).isEqualTo(2)
        assertThat(result.imported).isZero()
        assertThat(activities.count()).isZero()
        // The decision is still recorded, so the archive stays a known, enumerable set.
        assertThat(imports.findAll().map { it.outcome })
            .containsOnly(StravaImportOutcome.SKIPPED_UNSUPPORTED_TYPE)
    }

    @Test
    fun `heart rate the source lacks stays null and is never estimated`(@TempDir tmp: Path) {
        archiveWith(tmp, runRow(tmp, "1", "UUID-NOHR", withHeartRate = false))

        service.importArchive(StravaArchiveImportRequest())

        val activity = activities.findByExternalSourceAndExternalId(ExternalSource.STRAVA, "1").orElseThrow()
        assertThat(activity.averageHeartRate as Int?).isNull()
        assertThat(detail.samples(activity.id).map { it.heartRate }).containsOnlyNulls()
        assertThat(imports.findAll().single().qualityFlags).contains(StravaFitMapper.NO_HEART_RATE_IN_SOURCE)
    }

    @Test
    fun `laps are stored and the stream is recorded as FULL`(@TempDir tmp: Path) {
        archiveWith(tmp, runRow(tmp, "1", "UUID-1", lapDurations = listOf(6.0, 4.0)))

        service.importArchive(StravaArchiveImportRequest())

        val activity = activities.findByExternalSourceAndExternalId(ExternalSource.STRAVA, "1").orElseThrow()
        assertThat(detail.laps(activity.id)).hasSize(2)
        val part = detail.part(activity.id, DetailPayloadType.ACTIVITY_DETAILS_STREAM)
        assertThat(part?.status).isEqualTo(DetailPartStatus.NORMALIZED)
        // A FIT activity file IS the device recording, so completeness is a fact here, unlike the
        // Intervals stream of Phase 7E-1 which can only ever be UNKNOWN.
        assertThat(part?.sampleFidelity?.completeness).isEqualTo(SampleCompleteness.FULL)
    }

    // ---- idempotency and resume ----

    @Test
    fun `a second run over unchanged bytes adds nothing`(@TempDir tmp: Path) {
        archiveWith(tmp, runRow(tmp, "1", "UUID-1"), runRow(tmp, "2", "UUID-2"))
        service.importArchive(StravaArchiveImportRequest())
        val activitiesAfterFirst = activities.count()
        val samplesAfterFirst = jdbc.queryForObject("select count(*) from activity_sample", Int::class.java)

        val second = service.importArchive(StravaArchiveImportRequest())

        assertThat(second.imported).isZero()
        assertThat(second.alreadyImported).isEqualTo(2)
        // A re-run must not report the earlier run's writes as its own.
        assertThat(second.samplesWritten).isZero()
        assertThat(second.lapsWritten).isZero()
        assertThat(second.samplesEnriched).isZero()
        assertThat(activities.count()).isEqualTo(activitiesAfterFirst)
        assertThat(jdbc.queryForObject("select count(*) from activity_sample", Int::class.java))
            .isEqualTo(samplesAfterFirst)
    }

    @Test
    fun `a dry run writes nothing at all`(@TempDir tmp: Path) {
        archiveWith(tmp, runRow(tmp, "1", "UUID-1"))

        val result = service.importArchive(StravaArchiveImportRequest(dryRun = true))

        assertThat(result.imported).isEqualTo(1)
        assertThat(activities.count()).isZero()
        assertThat(imports.count()).isZero()
    }

    @Test
    fun `an interrupted run resumes and only does what is left`(@TempDir tmp: Path) {
        archiveWith(tmp, runRow(tmp, "1", "UUID-1"), runRow(tmp, "2", "UUID-2"))
        service.importArchive(StravaArchiveImportRequest())

        // Simulate "the JVM died after the first file": drop the second activity and its record.
        val second = activities.findByExternalSourceAndExternalId(ExternalSource.STRAVA, "2").orElseThrow()
        jdbc.update("delete from strava_activity_import where strava_activity_id = '2'")
        jdbc.update(
            """delete from activity_analysis_interval where activity_analysis_id in
               (select id from activity_analysis where activity_id = ?)""", second.id)
        jdbc.update(
            """delete from activity_analysis_interval_group where activity_analysis_id in
               (select id from activity_analysis where activity_id = ?)""", second.id)
        listOf("activity_analysis", "activity_detail_collection", "activity_sample", "activity_lap",
            "activity_detail",
        ).forEach { jdbc.update("delete from $it where activity_id = ?", second.id) }
        jdbc.update("delete from activity where id = ?", second.id)

        val resumed = service.importArchive(StravaArchiveImportRequest())

        assertThat(resumed.imported).isEqualTo(1)
        assertThat(resumed.alreadyImported).isEqualTo(1)
        assertThat(activities.count()).isEqualTo(2)
    }

    @Test
    fun `one unreadable file does not stop the rest of the archive`(@TempDir tmp: Path) {
        SyntheticFitFiles.writeArchive(tmp, listOf(
            SyntheticFitFiles.ArchiveRow("bad", "Aug 9, 2026, 12:36:56 AM", "Run", "bad.fit.gz",
                byteArrayOf(9, 9, 9, 9)),
            runRow(tmp, "good", "UUID-GOOD"),
        ))
        useArchive(tmp)

        val result = service.importArchive(StravaArchiveImportRequest())

        assertThat(result.failed).isEqualTo(1)
        assertThat(result.imported).isEqualTo(1)
        assertThat(imports.findAll().single { it.stravaActivityId == "bad" }.lastErrorCode)
            .isEqualTo("FIT_GZIP_UNREADABLE")
    }

    // ---- enriching what another source already owns ----

    @Test
    fun `an activity matched by apple health uuid is enriched, never duplicated`(@TempDir tmp: Path) {
        val uuid = "6839EC1E-EA0D-496F-B994-2FFCC6635A71"
        val existing = givenIntervalsImportedActivity(uuid, stravaId = "19929243577")
        archiveWith(tmp, runRow(tmp, "19929243577", uuid))

        val result = service.importArchive(StravaArchiveImportRequest())

        assertThat(result.enrichedExisting).isEqualTo(1)
        assertThat(result.imported).isZero()
        // Exactly one activity still: the archive attached to it rather than creating a second row.
        assertThat(activities.count()).isEqualTo(1)
        val record = imports.findAll().single()
        assertThat(record.matchedBy).isEqualTo(StravaMatchMethod.APPLE_HEALTH_UUID)
        assertThat(record.activityId).isEqualTo(existing)
    }

    @Test
    fun `enrichment adds the laps Intervals never had and keeps the heart rate it did`(@TempDir tmp: Path) {
        val uuid = "UUID-ENRICH"
        val activityId = givenIntervalsImportedActivity(uuid, stravaId = "77")
        assertThat(detail.laps(activityId)).isEmpty()
        archiveWith(tmp, runRow(tmp, "77", uuid, lapDurations = listOf(6.0, 4.0)))

        val result = service.importArchive(StravaArchiveImportRequest())

        assertThat(result.lapsWritten).isEqualTo(2)
        assertThat(detail.laps(activityId)).hasSize(2)
        // Heart rate the Intervals import supplied is untouched; power the FIT supplied is filled in.
        val samples = detail.samples(activityId)
        assertThat(samples.map { it.heartRate }).doesNotContainNull()
        assertThat(samples.count { it.power != null }).isGreaterThan(0)
        assertThat(result.samplesEnriched).isGreaterThan(0)
    }

    @Test
    fun `enrichment never overwrites a stored value and stamps provenance on what it adds`(@TempDir tmp: Path) {
        val uuid = "UUID-PROV"
        val activityId = givenIntervalsImportedActivity(uuid, stravaId = "78", power = 999.0)
        archiveWith(tmp, runRow(tmp, "78", uuid))

        service.importArchive(StravaArchiveImportRequest())

        val samples = detail.samples(activityId)
        assertThat(samples.map { it.power }).allMatch { it == 999.0 }
        assertThat(samples.mapNotNull { it.extraMetrics?.get("power_source") }).isEmpty()
    }

    @Test
    fun `laps a better source already supplied are left alone`(@TempDir tmp: Path) {
        val uuid = "UUID-LAPS"
        val activityId = givenIntervalsImportedActivity(uuid, stravaId = "79")
        detail.replaceLaps(activityId, listOf(
            com.runningai.activity.detail.LapData(lapIndex = 1, intensityType = "ACTIVE", workoutStepIndex = 1)))
        archiveWith(tmp, runRow(tmp, "79", uuid, lapDurations = listOf(6.0, 4.0)))

        service.importArchive(StravaArchiveImportRequest())

        val laps = detail.laps(activityId)
        assertThat(laps).hasSize(1)
        assertThat(laps.single().intensityType).isEqualTo("ACTIVE")
        assertThat(imports.findAll().single().qualityFlags).contains("LAPS_PRESENT_NOT_REPLACED")
    }

    @Test
    fun `laps this importer wrote itself are re-written, so a mapper fix can reach them`(@TempDir tmp: Path) {
        val uuid = "UUID-OWN"
        val activityId = givenIntervalsImportedActivity(uuid, stravaId = "80")
        archiveWith(tmp, runRow(tmp, "80", uuid, lapDurations = listOf(6.0, 4.0)))
        service.importArchive(StravaArchiveImportRequest())
        assertThat(detail.laps(activityId)).hasSize(2)

        // A changed file (new bytes) re-runs the mapper over an activity whose laps this importer owns.
        archiveWith(tmp, runRow(tmp, "80", uuid, lapDurations = listOf(3.0, 3.0, 4.0)))
        val second = service.importArchive(StravaArchiveImportRequest())

        assertThat(second.enrichedExisting).isEqualTo(1)
        assertThat(detail.laps(activityId)).hasSize(3)
        assertThat(imports.findAll().single().qualityFlags).doesNotContain("LAPS_PRESENT_NOT_REPLACED")
    }

    @Test
    fun `written laps reach the analysis even when the lap COUNT is unchanged`(@TempDir tmp: Path) {
        // The digest summarises a collection part by status and row count, so a lap set replaced by the
        // same number of richer laps looks identical to it. The importer must state the change itself.
        val uuid = "UUID-REANALYSE"
        val activityId = givenIntervalsImportedActivity(uuid, stravaId = "81")
        archiveWith(tmp, runRow(tmp, "81", uuid, lapDurations = listOf(6.0, 4.0)))

        service.importArchive(StravaArchiveImportRequest())

        // The FIT laps state no avg_speed, so lap speed is RunningAI's distance/time - and it has to
        // have reached the stored analysis, not merely the lap rows.
        val stored = analysis.find(activityId)
        assertThat(stored).isNotNull
        assertThat(stored!!.laps.lapCount).isEqualTo(2)
        assertThat(stored.laps.speedMean).isNotNull
        assertThat(detail.laps(activityId).map { it.averageSpeed }).doesNotContainNull()
    }

    @Test
    fun `a start-time collision is reported, never merged and never duplicated`(@TempDir tmp: Path) {
        // An existing activity with no identifier this archive could match on.
        givenPlainActivity(start)
        archiveWith(tmp, runRow(tmp, "collides", "UUID-UNKNOWN"))

        val result = service.importArchive(StravaArchiveImportRequest())

        assertThat(result.skippedAmbiguous).isEqualTo(1)
        assertThat(result.imported).isZero()
        assertThat(activities.count()).isEqualTo(1)
        assertThat(imports.findAll().single().reasonCode).startsWith("START_TIME_COLLIDES_WITH_")
    }

    // ---- safety ----

    @Test
    fun `the importer refuses to run while a publishing switch is on`() {
        assertThatThrownBy {
            System.setProperty("running-ai.workout-publishing.enabled", "true")
            service.importArchive(StravaArchiveImportRequest())
        }.isInstanceOf(UnsafeStravaImportRuntimeException::class.java)
        System.clearProperty("running-ai.workout-publishing.enabled")
    }

    // ---- fixtures ----

    /**
     * An activity in the state Phase 7E-1 leaves behind: imported from Intervals.icu, heart rate on
     * every sample, and - crucially - zero laps, because Intervals reports `icu_lap_count` = 0.
     */
    private fun givenIntervalsImportedActivity(
        appleHealthUuid: String,
        stravaId: String,
        power: Double? = null,
    ): Long {
        val athleteId = athletes.defaultAthlete.id
        val activity = activities.save(com.runningai.activity.Activity(
            athleteId, ExternalSource.INTERVALS_ICU, "i-$stravaId", ActivityType.RUN, start, 10, 100.0,
            null, null))
        detail.replaceSamples(activity.id, (0..9).map { i ->
            SampleData(
                sampleIndex = i,
                sampleTime = start.plusSeconds(i.toLong()),
                elapsedSeconds = i.toDouble(),
                heartRate = 150.0 + i,
                speed = 3.0,
                power = power,
            )
        })
        jdbc.update(
            """insert into intervals_activity_import
               (athlete_id, intervals_activity_id, activity_id, outcome, source_label, source_external_id,
                strava_activity_id, started_at, attempt_count, created_at, updated_at)
               values (?, ?, ?, 'IMPORTED', 'OAUTH_CLIENT', ?, ?, ?, 1, ?, ?)""",
            athleteId, "i-$stravaId", activity.id, appleHealthUuid, stravaId,
            java.sql.Timestamp.from(start), java.sql.Timestamp.from(start), java.sql.Timestamp.from(start),
        )
        return activity.id
    }

    private fun givenPlainActivity(startedAt: Instant): Long = activities.save(
        com.runningai.activity.Activity(
            athletes.defaultAthlete.id, ExternalSource.GARMIN, "g-1", ActivityType.RUN, startedAt, 10, 100.0,
            null, null)
    ).id
}
