package com.runningai.integration.strava

import com.fasterxml.jackson.databind.ObjectMapper
import com.runningai.activity.Activity
import com.runningai.activity.ActivityRepository
import com.runningai.activity.ActivityService
import com.runningai.activity.ExternalSource
import com.runningai.activity.detail.ActivityDetailStore
import com.runningai.activity.detail.ActivityRawPayloadStore
import com.runningai.activity.detail.DetailPartRecord
import com.runningai.activity.detail.DetailPartStatus
import com.runningai.activity.detail.DetailPayloadType
import com.runningai.activity.detail.SampleCompleteness
import com.runningai.activity.detail.SampleStreamFidelity
import com.runningai.analysis.RunningActivityAnalysisService
import com.runningai.athlete.AthleteService
import com.runningai.enrichment.IntervalsActivityImportRepository
import com.runningai.enrichment.StravaActivityImportStore
import com.runningai.enrichment.StravaImportOutcome
import com.runningai.enrichment.StravaImportRecord
import com.runningai.enrichment.StravaMatchMethod
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.core.env.Environment
import org.springframework.stereotype.Service
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.Path
import kotlin.math.abs
import kotlin.math.roundToInt

/** The Strava archive importer is switched off. */
class StravaArchiveImportDisabledException :
    RuntimeException("Strava archive import is disabled (running-ai.strava.archive-import.enabled=false)")

/** An import is already executing in this JVM. */
class StravaArchiveImportAlreadyRunningException :
    RuntimeException("A Strava archive import is already running")

/** The runtime is not in the state the importer requires. */
class UnsafeStravaImportRuntimeException(val unsafeFlags: List<String>) :
    RuntimeException("Strava archive import refused: unsafe runtime flags enabled: $unsafeFlags")

@ConfigurationProperties(prefix = "running-ai.strava.archive-import")
data class StravaArchiveImportProperties(
    /** Master switch. Off by default: this path creates activity rows. */
    val enabled: Boolean = false,
    /**
     * Directory holding the unpacked Strava export (`activities.csv` + `activities/`). It must live
     * OUTSIDE the repository - the export contains the athlete's real GPS traces - and is only read.
     */
    val archivePath: String = "",
    /** Safety cap on files processed in one invocation; the rest are left for the next call. */
    val maxActivities: Int = 200,
)

data class StravaArchiveImportRequest(
    /** Reports what every candidate would become, writing nothing. */
    val dryRun: Boolean = false,
)

data class StravaArchiveImportResult(
    val archivePath: String,
    val dryRun: Boolean,
    val filesInArchive: Int,
    val filesMissing: Int,
    val rowsUnparsable: Int,
    val processed: Int,
    val imported: Int,
    val refreshed: Int,
    val alreadyImported: Int,
    val enrichedExisting: Int,
    val alreadyEnriched: Int,
    val skippedAmbiguous: Int,
    val skippedUnsupportedType: Int,
    val skippedNoSensorData: Int,
    val failed: Int,
    val samplesWritten: Int,
    val lapsWritten: Int,
    val samplesEnriched: Int,
    val stopReason: String?,
    val items: List<StravaImportRecord>,
)

/**
 * Imports the athlete's own Strava data export (Phase 7E-2).
 *
 * **Why a file importer and not an API client.** These activities are unreachable over any API:
 * Intervals.icu answers `422 Cannot read Strava activities via the API` for every one of them, and
 * Garmin Connect has no record before 2026-09-18. The export is the only surviving copy, so the source
 * here is a local directory. No Strava HTTP client exists in this repository and none is added.
 *
 * **What it adds, measured over the real archive before it was written:**
 *  - 15 activities that exist in no other system (11 indoor rides, 4 treadmill runs, 2026-06-06..08-15);
 *  - the real laps of the 25 activities Phase 7E-1 imported, every one of which has zero laps stored
 *    because Intervals.icu reports `icu_lap_count` = 0 for all of them;
 *  - running power where the FIT recorded more of it than the Intervals stream did.
 *
 * **Identity is an exact join, never a heuristic.** Strava re-encodes each Apple Health workout into
 * FIT and stamps the developer field `apple_health_uuid` into the session message; that value is
 * character-for-character the `external_id` Intervals.icu holds for the same workout - measured 27/27,
 * 26 of them independently corroborated by a matching Strava activity id, with zero contradictions. A
 * candidate is therefore matched by identifier alone, and one that matches nothing is only then a new
 * activity. A conservative start-time guard still refuses to create an activity that would land on top
 * of an existing one, so a missing identifier can never produce a duplicate.
 *
 * **It never overwrites a stored value.** For a matched activity the FIT contributes laps (where there
 * are none) and fills sample metrics that are NULL, each stamped with its provenance - see
 * [StravaSampleEnricher]. Heart rate that only Intervals has, and power that only the FIT has, both
 * survive.
 *
 * **Raw-first and idempotent**, like every other ingestion here: the decoded file is stored in
 * `activity_raw_payload` before any mapper runs, and the committed per-file row in
 * `strava_activity_import` carries the file's SHA-256, so a second run over unchanged bytes writes
 * nothing. Re-invoking the endpoint IS both the resume and the retry; there is no automatic retry.
 */
@Service
class StravaArchiveImportService(
    private val archive: StravaArchive,
    private val decoder: FitActivityDecoder,
    private val mapper: StravaFitMapper,
    private val enricher: StravaSampleEnricher,
    private val athleteService: AthleteService,
    private val activities: ActivityRepository,
    private val activityService: ActivityService,
    private val detailStore: ActivityDetailStore,
    private val rawPayloads: ActivityRawPayloadStore,
    private val importStore: StravaActivityImportStore,
    private val intervalsImports: IntervalsActivityImportRepository,
    private val analysisService: RunningActivityAnalysisService,
    private val properties: StravaArchiveImportProperties,
    private val objectMapper: ObjectMapper,
    private val environment: Environment,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(StravaArchiveImportService::class.java)
    private val inFlight = AtomicBoolean(false)

    fun importArchive(request: StravaArchiveImportRequest): StravaArchiveImportResult {
        if (!properties.enabled) throw StravaArchiveImportDisabledException()
        requireSafeRuntime()
        if (properties.archivePath.isBlank()) {
            throw StravaArchiveException("STRAVA_ARCHIVE_PATH_NOT_SET",
                "running-ai.strava.archive-import.archive-path is not set")
        }
        if (!inFlight.compareAndSet(false, true)) throw StravaArchiveImportAlreadyRunningException()
        try {
            return run(Path(properties.archivePath), request.dryRun)
        } finally {
            inFlight.set(false)
        }
    }

    /** Everything the importer has ever recorded for this athlete. DB-only, reads no file. */
    fun records(): List<StravaImportRecord> = importStore.all(athleteService.defaultAthlete.id)

    // ---- orchestration ----

    private fun run(root: Path, dryRun: Boolean): StravaArchiveImportResult {
        val athleteId = athleteService.defaultAthlete.id
        val listing = archive.entries(root)
        val results = mutableListOf<StravaImportRecord>()
        var stopReason: String? = null

        for (entry in listing.entries.take(properties.maxActivities)) {
            try {
                results += process(athleteId, entry, dryRun)
            } catch (e: Exception) {
                // One unreadable file must not cost the other 93: it is recorded and the run continues.
                val code = (e as? FitDecodeException)?.code
                    ?: (e as? StravaMappingException)?.code
                    ?: e.javaClass.simpleName
                log.warn("Strava archive file failed: stravaActivityId={} code={}", entry.stravaActivityId, code)
                results += persist(athleteId, baseRecord(entry).copy(
                    outcome = StravaImportOutcome.FAILED, reasonCode = code, lastErrorCode = code,
                ), dryRun, 1)
            }
        }
        val result = summarize(root.toString(), dryRun, listing, stopReason, results)
        log.info(
            "Strava archive import finished: dryRun={} files={} imported={} enriched={} skipped={} failed={} " +
                "samples={} laps={} samplesEnriched={}",
            dryRun, result.filesInArchive, result.imported + result.refreshed, result.enrichedExisting,
            result.alreadyImported + result.alreadyEnriched + result.skippedAmbiguous +
                result.skippedUnsupportedType + result.skippedNoSensorData,
            result.failed, result.samplesWritten, result.lapsWritten, result.samplesEnriched,
        )
        return result
    }

    private fun process(athleteId: Long, entry: StravaArchiveEntry, dryRun: Boolean): StravaImportRecord {
        val stored = importStore.find(athleteId, entry.stravaActivityId)
        val bytes = entry.readBytes()
        val sha = archive.sha256(bytes)

        // Unchanged bytes, already dealt with: nothing to decide and nothing to write. This is what
        // makes a second run add zero rows.
        if (stored != null && stored.outcome.stored && stored.fileSha256 == sha) {
            val unchanged = if (stored.outcome == StravaImportOutcome.ENRICHED_EXISTING ||
                stored.outcome == StravaImportOutcome.SKIPPED_ALREADY_ENRICHED
            ) {
                StravaImportOutcome.SKIPPED_ALREADY_ENRICHED
            } else {
                StravaImportOutcome.SKIPPED_ALREADY_IMPORTED
            }
            return persist(athleteId, stored.copy(
                outcome = unchanged, reasonCode = "UNCHANGED_SINCE_LAST_IMPORT",
            ), dryRun, 0)
        }

        val fit = decoder.decodeGzipped(bytes)
        val base = baseRecord(entry).copy(
            fileSha256 = sha,
            appleHealthUuid = fit.appleHealthUuid,
            deviceManufacturer = fit.manufacturer,
            fitSport = fit.sport,
            fitSubSport = fit.subSport,
            activityType = mapper.activityTypeOf(fit)?.name,
            startedAt = fit.startedAt ?: entry.startedAt,
            elapsedTimeSeconds = fit.elapsedTimeSeconds?.roundToInt(),
            timerTimeSeconds = fit.timerTimeSeconds?.roundToInt(),
            distanceMeters = fit.distanceMeters?.takeIf { it > 0.0 },
            recordCount = fit.records.size,
            lapCount = fit.laps.size,
            sensorChannels = fit.sensorChannels.joinToString(",").take(400).ifEmpty { null },
            qualityFlags = mapper.qualityFlags(fit).joinToString(",").take(400).ifEmpty { null },
        )

        if (fit.records.isEmpty()) {
            return persist(athleteId, base.copy(
                outcome = StravaImportOutcome.SKIPPED_NO_SENSOR_DATA, reasonCode = "FIT_HAS_NO_RECORDS",
            ), dryRun, 1)
        }

        // Identity first. Both keys are exact; neither involves a tolerance.
        val match = findExisting(athleteId, entry, fit)
        if (match != null) {
            return if (dryRun) {
                base.copy(activityId = match.activity.id, outcome = StravaImportOutcome.ENRICHED_EXISTING,
                    matchedBy = match.method, reasonCode = "DRY_RUN")
            } else {
                enrichExisting(athleteId, match, entry, fit, base, stored)
            }
        }

        if (mapper.activityTypeOf(fit) == null) {
            return persist(athleteId, base.copy(
                outcome = StravaImportOutcome.SKIPPED_UNSUPPORTED_TYPE,
                reasonCode = "NO_ACTIVITY_TYPE_FOR_${fit.sport}_${fit.subSport}".take(64),
            ), dryRun, 1)
        }

        // Identity said "new". Before creating anything, refuse to land on top of a stored activity
        // that starts at effectively the same moment: an identifier this archive happens not to carry
        // must never become a duplicate row.
        val collisions = collidingActivities(athleteId, fit.startedAt ?: entry.startedAt)
        if (collisions.isNotEmpty()) {
            return persist(athleteId, base.copy(
                outcome = StravaImportOutcome.SKIPPED_AMBIGUOUS,
                reasonCode = "START_TIME_COLLIDES_WITH_${collisions.size}",
            ), dryRun, 1)
        }

        if (dryRun) {
            return base.copy(outcome = StravaImportOutcome.IMPORTED, reasonCode = "DRY_RUN",
                matchedBy = StravaMatchMethod.NONE)
        }
        return importOne(athleteId, entry, fit, base)
    }

    // ---- creating a new activity ----

    private fun importOne(
        athleteId: Long,
        entry: StravaArchiveEntry,
        fit: FitActivity,
        base: StravaImportRecord,
    ): StravaImportRecord {
        val now = Instant.now(clock)
        val normalized = mapper.toNormalizedActivity(entry, fit)
        val existing = activities
            .findByExternalSourceAndExternalId(ExternalSource.STRAVA, entry.stravaActivityId).orElse(null)

        // Raw first, in its own committed transaction: a later mapping failure never loses the data,
        // and a reprocess never has to touch the archive again.
        rawPayloads.saveOrReplace(
            athleteId, ExternalSource.STRAVA, entry.stravaActivityId,
            DetailPayloadType.ACTIVITY_DETAILS_STREAM, objectMapper.valueToTree(fit), now,
        )

        val activity = activityService.upsertExternalActivity(normalized).activity()
        val activityId = activity.id
        val startedAt = requireNotNull(fit.startedAt)

        detailStore.replaceDetail(activityId, mapper.toDetail(fit))
        detailStore.recordPart(activityId, DetailPartRecord(
            DetailPayloadType.ACTIVITY_DETAIL, DetailPartStatus.NORMALIZED, null, 1, now))

        val laps = mapper.toLaps(fit)
        val lapsWritten = if (laps.isEmpty()) 0 else detailStore.replaceLaps(activityId, laps)
        detailStore.recordPart(activityId, DetailPartRecord(
            DetailPayloadType.SPLITS,
            if (lapsWritten == 0) DetailPartStatus.EMPTY else DetailPartStatus.NORMALIZED,
            null, lapsWritten, now))

        val samples = mapper.toSamples(fit, startedAt)
        val written = detailStore.replaceSamples(activityId, samples)
        detailStore.recordPart(activityId, DetailPartRecord(
            payloadType = DetailPayloadType.ACTIVITY_DETAILS_STREAM,
            status = if (written == 0) DetailPartStatus.EMPTY else DetailPartStatus.NORMALIZED,
            errorCode = null,
            itemCount = written,
            attemptedAt = now,
            // A FIT activity file IS the device's own recording: every record message it contains is
            // stored, and the file states no larger native count to fall short of. Unlike the
            // Intervals stream (Phase 7E-1), there is a source statement to check against, and it
            // agrees - so FULL is a fact here rather than an assumption.
            sampleFidelity = SampleStreamFidelity(
                completeness = SampleCompleteness.FULL,
                requestedMaxChartSize = null,
                sourceMetricsCount = written,
                sourceTotalMetricsCount = fit.records.size,
            ),
        ))

        val quality = mapper.qualityFlags(fit).toMutableList()
        runAnalysis(activityId, dataChanged = true)?.let { quality += it }

        return persist(athleteId, base.copy(
            activityId = activityId,
            outcome = if (existing == null) StravaImportOutcome.IMPORTED else StravaImportOutcome.REFRESHED,
            matchedBy = StravaMatchMethod.NONE,
            sampleCount = written,
            lapsWritten = lapsWritten,
            samplesEnriched = 0,
            qualityFlags = quality.joinToString(",").take(400).ifEmpty { null },
            importedAt = now,
        ), dryRun = false, attemptDelta = 1)
    }

    // ---- enriching an activity another source already owns ----

    private fun enrichExisting(
        athleteId: Long,
        match: ExistingMatch,
        entry: StravaArchiveEntry,
        fit: FitActivity,
        base: StravaImportRecord,
        previous: StravaImportRecord?,
    ): StravaImportRecord {
        val now = Instant.now(clock)
        val activityId = match.activity.id
        val quality = mapper.qualityFlags(fit).toMutableList()

        rawPayloads.saveOrReplace(
            athleteId, ExternalSource.STRAVA, entry.stravaActivityId,
            DetailPayloadType.ACTIVITY_DETAILS_STREAM, objectMapper.valueToTree(fit), now,
        )

        // Laps: written where there are none, and re-written only where THIS importer wrote them.
        // A source that supplied its own laps - Garmin, with real intensities and workout step indices -
        // is strictly better than a Strava re-encode, which carries neither, so its laps are never
        // touched. But refusing to replace laps this importer itself produced would mean a mapper fix
        // could never reach an already-enriched activity, so ownership is tracked rather than assumed.
        val storedLaps = detailStore.laps(activityId)
        val candidateLaps = mapper.toLaps(fit)
        val ownsStoredLaps = (previous?.lapsWritten ?: 0) > 0
        val lapsWritten = when {
            storedLaps.isNotEmpty() && !ownsStoredLaps -> { quality += LAPS_PRESENT_NOT_REPLACED; 0 }
            candidateLaps.isEmpty() -> 0
            else -> detailStore.replaceLaps(activityId, candidateLaps).also {
                detailStore.recordPart(activityId, DetailPartRecord(
                    DetailPayloadType.SPLITS, DetailPartStatus.NORMALIZED, null, it, now))
            }
        }

        // Samples: fill only what is NULL, never replace.
        val storedSamples = detailStore.samples(activityId)
        val overlaps = enricher.overlaps(
            fit, storedSamples.firstOrNull()?.sampleTime, storedSamples.lastOrNull()?.sampleTime)
        val enrichment = if (overlaps) {
            enricher.enrich(storedSamples, fit)
        } else {
            quality += SAMPLE_CLOCKS_DO_NOT_OVERLAP
            SampleEnrichment(storedSamples, 0, emptyMap())
        }
        if (enrichment.samplesEnriched > 0) {
            detailStore.replaceSamples(activityId, enrichment.samples)
            quality += enrichment.filledByMetric.map { (metric, count) -> "FILLED_${metric.uppercase()}_$count" }
        }

        runAnalysis(activityId, dataChanged = lapsWritten > 0 || enrichment.samplesEnriched > 0)
            ?.let { quality += it }

        return persist(athleteId, base.copy(
            activityId = activityId,
            outcome = StravaImportOutcome.ENRICHED_EXISTING,
            matchedBy = match.method,
            reasonCode = match.method.name,
            sampleCount = storedSamples.size,
            lapsWritten = lapsWritten,
            samplesEnriched = enrichment.samplesEnriched,
            qualityFlags = quality.joinToString(",").take(400).ifEmpty { null },
            importedAt = now,
        ), dryRun = false, attemptDelta = 1)
    }

    // ---- identity ----

    private data class ExistingMatch(val activity: Activity, val method: StravaMatchMethod)

    /**
     * The stored activity that already IS this workout, by exact identifier only.
     *
     * `apple_health_uuid` is tried first because it is the strongest statement available: it is the id
     * of the originating Apple Health workout, written independently by two different exporters.
     */
    private fun findExisting(athleteId: Long, entry: StravaArchiveEntry, fit: FitActivity): ExistingMatch? {
        val imports = intervalsImports.findByAthleteIdOrderByStartedAtAsc(athleteId)
        fit.appleHealthUuid?.let { uuid ->
            imports.firstOrNull { it.sourceExternalId == uuid && it.activityId != null }
                ?.let { row ->
                    activities.findById(row.activityId!!).orElse(null)
                        ?.let { return ExistingMatch(it, StravaMatchMethod.APPLE_HEALTH_UUID) }
                }
        }
        imports.firstOrNull { it.stravaActivityId == entry.stravaActivityId && it.activityId != null }
            ?.let { row ->
                activities.findById(row.activityId!!).orElse(null)
                    ?.let { return ExistingMatch(it, StravaMatchMethod.STRAVA_ACTIVITY_ID) }
            }
        return null
    }

    /**
     * Stored activities starting within [START_COLLISION_SECONDS] of [startedAt], excluding this
     * archive's own row for the same workout. Used only to refuse creating a probable duplicate.
     */
    private fun collidingActivities(athleteId: Long, startedAt: Instant): List<Activity> {
        val from = startedAt.minusSeconds(START_COLLISION_SECONDS)
        val toExclusive = startedAt.plusSeconds(START_COLLISION_SECONDS + 1)
        return activities
            .findByAthleteIdAndStartedAtGreaterThanEqualAndStartedAtLessThan(athleteId, from, toExclusive)
            .filter { it.externalSource != ExternalSource.STRAVA }
            .filter { abs(it.startedAt.epochSecond - startedAt.epochSecond) <= START_COLLISION_SECONDS }
    }

    // ---- shared ----

    /**
     * Recomputes the activity's analysis.
     *
     * [dataChanged] is passed when this importer has just written laps or filled samples. It is not a
     * shortcut past [com.runningai.analysis.AnalysisReusePolicy] but a fact the policy cannot see:
     * `analysis_input_digest` summarises each collection part by status and ROW COUNT, so replacing a
     * lap set with the same number of richer laps - which is exactly what adding a derived lap speed
     * does - leaves the digest identical and the stale analysis would be reused. The importer knows it
     * changed the data, so it states it rather than letting the digest infer it. When nothing was
     * written the ordinary policy decides, and an unchanged activity is not recomputed.
     */
    private fun runAnalysis(activityId: Long, dataChanged: Boolean): String? = try {
        val status = if (dataChanged) {
            analysisService.analyse(activityId).status.name
        } else {
            analysisService.analyseIfNeeded(activityId).analysis.status.name
        }
        status.takeIf { it != "COMPLETE" }?.let { "ANALYSIS_$it" }
    } catch (e: Exception) {
        log.warn("Analysis of Strava activity failed: activityId={} error={}", activityId, e.javaClass.simpleName)
        "ANALYSIS_FAILED_${e.javaClass.simpleName}"
    }

    private fun persist(
        athleteId: Long,
        record: StravaImportRecord,
        dryRun: Boolean,
        attemptDelta: Int,
    ): StravaImportRecord = if (dryRun) record else importStore.record(athleteId, record, attemptDelta)

    private fun baseRecord(entry: StravaArchiveEntry) = StravaImportRecord(
        stravaActivityId = entry.stravaActivityId,
        activityId = null,
        outcome = StravaImportOutcome.FAILED,
        reasonCode = null,
        archiveFilename = entry.filename,
        fileSha256 = null,
        appleHealthUuid = null,
        matchedBy = null,
        deviceManufacturer = null,
        fitSport = null,
        fitSubSport = null,
        activityType = null,
        startedAt = entry.startedAt,
        elapsedTimeSeconds = null,
        timerTimeSeconds = null,
        distanceMeters = null,
        recordCount = null,
        lapCount = null,
        sampleCount = null,
        lapsWritten = null,
        samplesEnriched = null,
        sensorChannels = null,
        qualityFlags = null,
    )

    private fun summarize(
        archivePath: String,
        dryRun: Boolean,
        listing: StravaArchiveListing,
        stopReason: String?,
        results: List<StravaImportRecord>,
    ) = StravaArchiveImportResult(
        archivePath = archivePath,
        dryRun = dryRun,
        filesInArchive = listing.entries.size,
        filesMissing = listing.missingFile,
        rowsUnparsable = listing.unparsable,
        processed = results.size,
        imported = results.count { it.outcome == StravaImportOutcome.IMPORTED },
        refreshed = results.count { it.outcome == StravaImportOutcome.REFRESHED },
        alreadyImported = results.count { it.outcome == StravaImportOutcome.SKIPPED_ALREADY_IMPORTED },
        enrichedExisting = results.count { it.outcome == StravaImportOutcome.ENRICHED_EXISTING },
        alreadyEnriched = results.count { it.outcome == StravaImportOutcome.SKIPPED_ALREADY_ENRICHED },
        skippedAmbiguous = results.count { it.outcome == StravaImportOutcome.SKIPPED_AMBIGUOUS },
        skippedUnsupportedType = results.count { it.outcome == StravaImportOutcome.SKIPPED_UNSUPPORTED_TYPE },
        skippedNoSensorData = results.count { it.outcome == StravaImportOutcome.SKIPPED_NO_SENSOR_DATA },
        failed = results.count { it.outcome == StravaImportOutcome.FAILED },
        // Counted over the outcomes that did work IN THIS RUN only. A skipped row still carries what
        // the earlier run wrote, and summing that would report a re-run as having rewritten everything.
        // Only a created/refreshed activity has samples WRITTEN by this importer; on an enriched one
        // sampleCount is the size of the stream another source owns, which is not this run's output.
        samplesWritten = results.filter { it.outcome in CREATED_THIS_RUN }.sumOf { it.sampleCount ?: 0 },
        lapsWritten = results.filter { it.outcome in WROTE_THIS_RUN }.sumOf { it.lapsWritten ?: 0 },
        samplesEnriched = results.filter { it.outcome in WROTE_THIS_RUN }.sumOf { it.samplesEnriched ?: 0 },
        stopReason = stopReason,
        items = results,
    )

    private fun requireSafeRuntime() {
        val unsafe = SAFETY_FLAGS.filter { environment.getProperty(it, Boolean::class.java, false) }
        if (unsafe.isNotEmpty()) throw UnsafeStravaImportRuntimeException(unsafe)
    }

    private companion object {
        /** Outcomes that wrote rows during the run being summarised. */
        val WROTE_THIS_RUN = setOf(
            StravaImportOutcome.IMPORTED,
            StravaImportOutcome.REFRESHED,
            StravaImportOutcome.ENRICHED_EXISTING,
        )

        /** Outcomes where this importer created the activity and therefore wrote its sample stream. */
        val CREATED_THIS_RUN = setOf(StravaImportOutcome.IMPORTED, StravaImportOutcome.REFRESHED)

        /** A stored activity this close to a candidate's start is treated as possibly the same workout. */
        const val START_COLLISION_SECONDS = 120L

        const val LAPS_PRESENT_NOT_REPLACED = "LAPS_PRESENT_NOT_REPLACED"
        const val SAMPLE_CLOCKS_DO_NOT_OVERLAP = "SAMPLE_CLOCKS_DO_NOT_OVERLAP"

        /**
         * The importer writes nothing outside this database, so the question it has to answer before
         * running is the same narrow one Phase 7E-1 asked: could something ELSE be publishing while
         * tens of thousands of rows appear? `running-ai.mcp.enabled` is deliberately absent for the
         * reason given there - the write tool is already covered by the publishing switch, and the
         * read-only tools must stay usable for verifying what an import produced.
         */
        val SAFETY_FLAGS = listOf(
            "running-ai.workout-publishing.enabled",
            "running-ai.workout-publishing.scheduler.enabled",
            "running-ai.draft-publishing.enabled",
            "running-ai.garmin.scheduler.enabled",
            "running-ai.garmin.profile-sync.scheduler.enabled",
        )
    }
}
