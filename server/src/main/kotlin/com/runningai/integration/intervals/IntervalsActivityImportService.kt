package com.runningai.integration.intervals

import com.fasterxml.jackson.databind.JsonNode
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
import com.runningai.enrichment.IntervalsActivityImportStore
import com.runningai.enrichment.IntervalsActivitySnapshot
import com.runningai.enrichment.IntervalsEnrichmentStore
import com.runningai.enrichment.IntervalsImportOutcome
import com.runningai.enrichment.IntervalsImportRecord
import com.runningai.enrichment.IntervalsPayloadType
import com.runningai.enrichment.MatchEvidence
import com.runningai.enrichment.MatchMethod
import com.runningai.enrichment.MatchResult
import com.runningai.enrichment.SourceLinkConflictException
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.core.env.Environment
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

/** The Intervals-only activity importer is switched off. */
class IntervalsActivityImportDisabledException :
    RuntimeException("Intervals activity import is disabled (running-ai.intervals.activity-import.enabled=false)")

/** An import is already executing in this JVM. */
class IntervalsActivityImportAlreadyRunningException :
    RuntimeException("An Intervals activity import is already running")

/** The runtime is not in the state the importer requires. */
class UnsafeIntervalsImportRuntimeException(val unsafeFlags: List<String>) :
    RuntimeException("Intervals activity import refused: unsafe runtime flags enabled: $unsafeFlags")

@ConfigurationProperties(prefix = "running-ai.intervals.activity-import")
data class IntervalsActivityImportProperties(
    /** Master switch. Off by default: this is the only path in the repo that creates activity rows
     *  from a source other than Garmin, so it is never on by accident. */
    val enabled: Boolean = false,
    /** Widest list window one invocation may ask Intervals.icu for, in days. */
    val maxRangeDays: Long = 120,
    /** Safety cap on activities processed in one invocation; the rest are left for the next call. */
    val maxActivities: Int = 200,
    /** Pause between two activities whose streams are actually fetched. */
    val activityDelay: Duration = Duration.ofSeconds(1),
)

data class IntervalsActivityImportRequest(
    val oldest: LocalDate? = null,
    val newest: LocalDate? = null,
    /** Reports what every candidate would become, writing nothing and fetching no stream. */
    val dryRun: Boolean = false,
)

data class IntervalsActivityImportResult(
    val oldest: LocalDate,
    val newest: LocalDate,
    val dryRun: Boolean,
    val candidatesSeen: Int,
    val candidatesUnmappable: Int,
    val processed: Int,
    val imported: Int,
    val refreshed: Int,
    val alreadyImported: Int,
    val linkedExisting: Int,
    val alreadyLinked: Int,
    val skippedGarminSourced: Int,
    val skippedAmbiguous: Int,
    val skippedUnsupportedType: Int,
    val skippedNoSourceData: Int,
    val failed: Int,
    val samplesWritten: Int,
    /** Set when the run stopped early; the remaining candidates were not processed. */
    val stopReason: String?,
    val items: List<IntervalsImportRecord>,
)

/**
 * Imports activities that exist ONLY in Intervals.icu (Phase 7E-1).
 *
 * Phase 7E-0 established why this path has to exist at all: Garmin Connect holds nothing before
 * 2026-09-18, and the 6H-6 historical backfill is driven entirely by Garmin discovery, so 27 real
 * sessions from 2026-08-02 to 09-13 are unreachable by it no matter how wide its window (measured: a
 * full 90-day re-run added zero rows).
 *
 * **Read-only towards Intervals.icu**: the only transport dependency is [IntervalsReadClient], which
 * declares no write method. Nothing here publishes anything anywhere.
 *
 * **It never creates a second row for a workout RunningAI already has.** Before importing anything the
 * candidate is matched against the stored activities of its own day through the live-verified
 * [IntervalsActivityMatcher] - the same tolerances, used in the same direction - and:
 *
 *  - one stored activity matches  -> the Intervals identity is ATTACHED to it (`activity_source_link`)
 *    and its Intervals training-model numbers are stored, enriching what is already there;
 *  - two or more match           -> nothing happens. An ambiguous identity is a human decision;
 *  - the payload names Garmin as its upstream -> it belongs to the 6H-5 enrichment path, not here;
 *  - nothing matches             -> and only then is a new activity created.
 *
 * **Raw-first**, like every other ingestion in the repo: the summary payload goes to
 * `intervals_raw_payload` and the stream payload to `activity_raw_payload` - each in its own committed
 * transaction - before any mapper runs, so a mapping failure loses nothing and a reprocess needs no API
 * call.
 *
 * **Idempotent and resumable.** `uk_activity_external` makes a second import of the same Intervals id
 * update the same row rather than add one, and the committed per-activity record in
 * `intervals_activity_import` means a re-invocation skips what is already done and retries only what
 * failed. There is no automatic retry: a per-activity failure is recorded and the run moves on; an
 * account-level failure (401/403/429/unreachable) stops the run where it stands, exactly as everywhere
 * else in this repo.
 *
 * **What it deliberately does not do.** No lap is invented for a source that has none (all 27 report
 * `icu_lap_count` = 0), so `activity_lap` is left untouched and the absence is recorded. Intervals' own
 * HR-zone times are not written to `activity_zone`: they are computed against Intervals' seven-zone
 * model while every stored zone row is Garmin's five-zone one, and mixing them would make a zone number
 * mean two different things. No Garmin activity id is ever synthesised.
 */
@Service
class IntervalsActivityImportService(
    private val readClient: IntervalsReadClient,
    private val athleteService: AthleteService,
    private val activities: ActivityRepository,
    private val activityService: ActivityService,
    private val candidateMapper: IntervalsImportCandidateMapper,
    // The 6H-5 enrichment mapper, reused unchanged: the training-model numbers an imported activity
    // carries must be read by exactly the same code that reads them for a Garmin-sourced one.
    private val enrichmentMapper: IntervalsActivityMapper,
    private val streamMapper: IntervalsStreamMapper,
    private val matcher: IntervalsActivityMatcher,
    private val enrichmentStore: IntervalsEnrichmentStore,
    private val importStore: IntervalsActivityImportStore,
    private val rawPayloads: ActivityRawPayloadStore,
    private val detailStore: ActivityDetailStore,
    private val analysisService: RunningActivityAnalysisService,
    private val properties: IntervalsActivityImportProperties,
    private val environment: Environment,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(IntervalsActivityImportService::class.java)
    private val inFlight = AtomicBoolean(false)

    fun importRange(request: IntervalsActivityImportRequest): IntervalsActivityImportResult {
        if (!properties.enabled) throw IntervalsActivityImportDisabledException()
        requireSafeRuntime()
        val athlete = athleteService.defaultAthlete
        val zone = ZoneId.of(athlete.timezone)
        val newest = request.newest ?: LocalDate.now(clock.withZone(zone))
        val oldest = request.oldest ?: newest.minusDays(properties.maxRangeDays - 1)
        require(!newest.isBefore(oldest)) { "newest must not be before oldest" }
        val days = java.time.temporal.ChronoUnit.DAYS.between(oldest, newest) + 1
        require(days <= properties.maxRangeDays) {
            "Import range is $days days; at most ${properties.maxRangeDays} allowed"
        }

        if (!inFlight.compareAndSet(false, true)) throw IntervalsActivityImportAlreadyRunningException()
        try {
            return run(athlete.id, zone, oldest, newest, request.dryRun)
        } finally {
            inFlight.set(false)
        }
    }

    /** Everything the importer has ever recorded for this athlete. DB-only, no Intervals call. */
    fun records(): List<IntervalsImportRecord> = importStore.all(athleteService.defaultAthlete.id)

    /**
     * Re-normalises one already-imported activity from its STORED raw payloads. Zero Intervals calls -
     * the fix for a mapper change is never a re-fetch while the payload is on disk.
     */
    fun reprocess(intervalsActivityId: String): IntervalsImportRecord {
        if (!properties.enabled) throw IntervalsActivityImportDisabledException()
        val athlete = athleteService.defaultAthlete
        val zone = ZoneId.of(athlete.timezone)
        val raw = enrichmentStore.findRaw(athlete.id, IntervalsPayloadType.ACTIVITY, intervalsActivityId)
            ?: throw com.runningai.common.exception.ResourceNotFoundException(
                "INTERVALS_RAW_NOT_FOUND", "No stored Intervals payload for $intervalsActivityId; import it first")
        val candidate = candidateMapper.map(raw.payload)
        val activity = activities.findByExternalSourceAndExternalId(ExternalSource.INTERVALS_ICU, intervalsActivityId)
            .orElseThrow {
                com.runningai.common.exception.ResourceNotFoundException(
                    "ACTIVITY_NOT_FOUND", "No imported activity for $intervalsActivityId")
            }
        val streams = rawPayloads.find(ExternalSource.INTERVALS_ICU, intervalsActivityId, DetailPayloadType.ACTIVITY_DETAILS_STREAM)
        val outcome = normalize(activity, candidate, streams, zone, raw.fetchedAt, IntervalsImportOutcome.REFRESHED)
        // fetchedAt stays the raw payload's real fetch time: a reprocess is not a fetch.
        enrichmentStore.upsertMetrics(activity.id, enrichmentMapper.map(raw.payload), raw.fetchedAt)
        return importStore.record(athlete.id, outcome, 1)
    }

    // ---- orchestration ----

    private fun run(
        athleteId: Long,
        zone: ZoneId,
        oldest: LocalDate,
        newest: LocalDate,
        dryRun: Boolean,
    ): IntervalsActivityImportResult {
        // One list GET for the whole window. Per-activity summary GETs would add 27 requests for data
        // the list already carries.
        val body = readClient.listActivities(oldest, newest).body()
        if (!body.isArray) {
            throw IntervalsException(IntervalsException.Reason.INVALID_RESPONSE, null,
                "Intervals.icu activities response is not a JSON array")
        }
        var unmappable = 0
        val candidates = mutableListOf<Pair<JsonNode, IntervalsImportCandidate>>()
        body.forEach { item ->
            try {
                candidates += item to candidateMapper.map(item)
            } catch (e: IntervalsMappingException) {
                unmappable++
                log.warn("Intervals import candidate skipped: code={} (raw item not stored without an id)", e.code)
            }
        }
        candidates.sortBy { it.second.startDate ?: Instant.EPOCH }

        val results = mutableListOf<IntervalsImportRecord>()
        var stopReason: String? = null
        var fetchedThisRun = false
        for ((item, candidate) in candidates.take(properties.maxActivities)) {
            try {
                val (record, fetched) = process(athleteId, zone, item, candidate, dryRun, fetchedThisRun)
                fetchedThisRun = fetchedThisRun || fetched
                results += record
            } catch (e: IntervalsException) {
                // Account-level: every further request would fail the same way. Stop, record, never retry.
                stopReason = e.code
                results += importStore.record(athleteId, failureRecord(candidate, e.code), 1)
                log.warn("Intervals import stopped: intervalsActivityId={} reason={}",
                    candidate.intervalsActivityId, e.code)
                break
            }
        }
        val result = summarize(oldest, newest, dryRun, candidates.size, unmappable, stopReason, results)
        log.info(
            "Intervals activity import finished: window={}..{} dryRun={} candidates={} imported={} linked={} " +
                "skipped={} failed={} samples={} stopReason={}",
            oldest, newest, dryRun, result.candidatesSeen, result.imported + result.refreshed,
            result.linkedExisting, result.alreadyImported + result.alreadyLinked + result.skippedGarminSourced +
                result.skippedAmbiguous + result.skippedUnsupportedType + result.skippedNoSourceData,
            result.failed, result.samplesWritten, stopReason,
        )
        return result
    }

    /** @return the recorded outcome, and whether this candidate actually called Intervals.icu. */
    private fun process(
        athleteId: Long,
        zone: ZoneId,
        item: JsonNode,
        candidate: IntervalsImportCandidate,
        dryRun: Boolean,
        alreadyFetched: Boolean,
    ): Pair<IntervalsImportRecord, Boolean> {
        val base = baseRecord(candidate)

        if (candidate.noSourceData) {
            return persist(athleteId, base.copy(
                outcome = IntervalsImportOutcome.SKIPPED_NO_SOURCE_DATA,
                reasonCode = "SOURCE_DATA_UNAVAILABLE",
            ), dryRun, 0) to false
        }

        // The enrichment snapshot comes from the 6H-5 mapper, so an imported activity's Intervals
        // training load, intensity and CTL/ATL are read exactly as a Garmin-sourced one's are.
        val snapshot = enrichmentMapper.map(item)
        val stored = importStore.find(athleteId, candidate.intervalsActivityId)
        val existingImport = activities
            .findByExternalSourceAndExternalId(ExternalSource.INTERVALS_ICU, candidate.intervalsActivityId)
            .orElse(null)

        // Already imported and the source has not been recomputed since: nothing to do, and no stream
        // fetch. This is what makes a second run add zero rows.
        if (existingImport != null && stored != null && stored.outcome.stored &&
            stored.sourceAnalyzedAt == candidate.analyzedAt
        ) {
            return persist(athleteId, base.copy(
                activityId = existingImport.id,
                outcome = IntervalsImportOutcome.SKIPPED_ALREADY_IMPORTED,
                reasonCode = "UNCHANGED_SINCE_LAST_IMPORT",
                sampleCount = stored.sampleCount,
                streamTypesAvailable = stored.streamTypesAvailable,
                streamTypesImported = stored.streamTypesImported,
                missingStreamTypes = stored.missingStreamTypes,
                sampleSpanSeconds = stored.sampleSpanSeconds,
                qualityFlags = stored.qualityFlags,
                importedAt = stored.importedAt,
            ), dryRun, 0) to false
        }

        // An activity Intervals received FROM Garmin is the 6H-5 enrichment path's subject, never this
        // importer's: importing it would create a second row for a workout Garmin already owns.
        if (candidate.source == IntervalsActivityMatcher.GARMIN_SOURCE_LABEL) {
            val garmin = candidate.externalId
                ?.let { activities.findByExternalSourceAndExternalId(ExternalSource.GARMIN, it).orElse(null) }
            return if (garmin == null) {
                persist(athleteId, base.copy(
                    outcome = IntervalsImportOutcome.SKIPPED_GARMIN_SOURCED,
                    reasonCode = "GARMIN_UPSTREAM_NOT_INGESTED",
                ), dryRun, 0) to false
            } else {
                link(athleteId, garmin, snapshot, base, MatchMethod.SOURCE_ID, MatchEvidence(null, null, null), dryRun) to false
            }
        }

        when (val duplicate = findDuplicate(candidate, snapshot, zone)) {
            is DuplicateSearch.Ambiguous -> return persist(athleteId, base.copy(
                outcome = IntervalsImportOutcome.SKIPPED_AMBIGUOUS,
                reasonCode = "MULTIPLE_CANDIDATE_ACTIVITIES_${duplicate.count}",
            ), dryRun, 0) to false

            is DuplicateSearch.Found -> return link(
                athleteId, duplicate.activity, snapshot, base, duplicate.method, duplicate.evidence, dryRun,
            ) to false

            DuplicateSearch.None -> Unit
        }

        if (IntervalsImportCandidateMapper.activityTypeOf(candidate.type) == null) {
            return persist(athleteId, base.copy(
                outcome = IntervalsImportOutcome.SKIPPED_UNSUPPORTED_TYPE,
                reasonCode = "NO_ACTIVITY_TYPE_FOR_${candidate.type}",
            ), dryRun, 0) to false
        }

        if (dryRun) {
            return base.copy(
                activityId = existingImport?.id,
                outcome = if (existingImport == null) IntervalsImportOutcome.IMPORTED else IntervalsImportOutcome.REFRESHED,
                reasonCode = "DRY_RUN",
            ) to false
        }

        if (alreadyFetched) sleep(properties.activityDelay)
        return importOne(athleteId, zone, item, candidate, base, existingImport) to true
    }

    private fun importOne(
        athleteId: Long,
        zone: ZoneId,
        item: JsonNode,
        candidate: IntervalsImportCandidate,
        base: IntervalsImportRecord,
        existing: Activity?,
    ): IntervalsImportRecord {
        val now = Instant.now(clock)
        val normalized = try {
            candidateMapper.toNormalizedActivity(candidate, zone)
        } catch (e: IntervalsImportValidationException) {
            log.warn("Intervals import rejected: intervalsActivityId={} code={}", candidate.intervalsActivityId, e.code)
            return importStore.record(athleteId, failureRecord(candidate, e.code), 1)
        }

        // Raw first, own committed transaction: a later mapping failure never loses the payload.
        enrichmentStore.saveOrReplaceRaw(
            athleteId, IntervalsPayloadType.ACTIVITY, candidate.intervalsActivityId,
            candidate.startDate?.atZone(zone)?.toLocalDate(), item, now,
        )

        val upsert = activityService.upsertExternalActivity(normalized)
        val activity = upsert.activity()

        val streams = try {
            readClient.getActivityStreams(candidate.intervalsActivityId).body()
        } catch (e: IntervalsException) {
            // The activity row exists and is correct; only its streams are missing. An account-level
            // reason propagates and stops the run, anything else is this activity's own failure.
            if (e.reason in STOP_REASONS) throw e
            log.warn("Intervals stream fetch failed: intervalsActivityId={} code={}", candidate.intervalsActivityId, e.code)
            return importStore.record(athleteId, failureRecord(candidate, e.code).copy(activityId = activity.id), 1)
        }
        rawPayloads.saveOrReplace(
            athleteId, ExternalSource.INTERVALS_ICU, candidate.intervalsActivityId,
            DetailPayloadType.ACTIVITY_DETAILS_STREAM, streams, now,
        )

        val outcome = if (existing == null) IntervalsImportOutcome.IMPORTED else IntervalsImportOutcome.REFRESHED
        val record = normalize(activity, candidate, streams, zone, now, outcome)
        enrichmentStore.upsertMetrics(activity.id, enrichmentMapper.map(item), now)
        return importStore.record(athleteId, record, 1)
    }

    /**
     * Maps the stored payloads into the normalised tables and records what the result is worth.
     * Shared by a fresh import and by [reprocess], so the two can never drift apart.
     */
    private fun normalize(
        activity: Activity,
        candidate: IntervalsImportCandidate,
        streams: JsonNode?,
        zone: ZoneId,
        attemptedAt: Instant,
        outcome: IntervalsImportOutcome,
    ): IntervalsImportRecord {
        val activityId = activity.id
        detailStore.replaceDetail(activityId, candidateMapper.toDetail(candidate))
        detailStore.recordPart(activityId, DetailPartRecord(
            DetailPayloadType.ACTIVITY_DETAIL, DetailPartStatus.NORMALIZED, null, 1, attemptedAt,
        ))

        // The source reports its own lap count, and it is 0 on every Intervals-only activity. Recording
        // the emptiness is the point: `activity_lap` stays untouched and nothing downstream has to work
        // out whether laps were missing or never existed.
        detailStore.recordPart(activityId, DetailPartRecord(
            DetailPayloadType.SPLITS, DetailPartStatus.EMPTY, null, candidate.lapCount ?: 0, attemptedAt,
        ))

        if (streams == null) {
            detailStore.recordPart(activityId, DetailPartRecord(
                DetailPayloadType.ACTIVITY_DETAILS_STREAM, DetailPartStatus.EMPTY, null, 0, attemptedAt,
            ))
            return baseRecord(candidate).copy(
                activityId = activityId, outcome = outcome,
                qualityFlags = IntervalsStreamMapper.NO_STREAMS, sampleCount = 0, importedAt = attemptedAt,
            )
        }

        val mapped = try {
            streamMapper.map(streams, requireNotNull(candidate.startDate))
        } catch (e: IntervalsMappingException) {
            detailStore.recordPart(activityId, DetailPartRecord(
                DetailPayloadType.ACTIVITY_DETAILS_STREAM, DetailPartStatus.MAPPING_FAILED, e.code, null, attemptedAt,
            ))
            return baseRecord(candidate).copy(
                activityId = activityId, outcome = IntervalsImportOutcome.FAILED,
                reasonCode = e.code, lastErrorCode = e.code,
            )
        }

        val written = detailStore.replaceSamples(activityId, mapped.samples)
        val quality = mapped.qualityFlags.toMutableList()
        val elapsed = candidate.elapsedTimeSeconds
        if (elapsed != null && mapped.spanSeconds != null && mapped.spanSeconds != elapsed) {
            quality += "STREAM_SPAN_NOT_ELAPSED_TIME"
        }
        detailStore.recordPart(activityId, DetailPartRecord(
            payloadType = DetailPayloadType.ACTIVITY_DETAILS_STREAM,
            status = if (written == 0) DetailPartStatus.EMPTY else DetailPartStatus.NORMALIZED,
            errorCode = null,
            itemCount = written,
            attemptedAt = attemptedAt,
            // Intervals.icu states no native point count and its streams endpoint takes no size
            // parameter, so there is nothing that could prove the stream is complete. UNKNOWN is the
            // honest answer: V14's FULL means "every point the source says exists was stored", and no
            // source statement to check against exists here. What IS known is recorded instead -
            // the stored point count - plus `icu_median_time_delta` = 1 observed on all 27 activities.
            sampleFidelity = SampleStreamFidelity(
                completeness = SampleCompleteness.UNKNOWN,
                requestedMaxChartSize = null,
                sourceMetricsCount = written,
                sourceTotalMetricsCount = null,
            ),
        ))

        val refreshed = try {
            analysisService.analyseIfNeeded(activityId)
        } catch (e: Exception) {
            log.warn("Analysis of imported activity failed: activityId={} error={}", activityId, e.javaClass.simpleName)
            quality += "ANALYSIS_FAILED_${e.javaClass.simpleName}"
            null
        }
        if (refreshed != null && refreshed.analysis.status.name != "COMPLETE") {
            quality += "ANALYSIS_${refreshed.analysis.status.name}"
        }

        return baseRecord(candidate).copy(
            activityId = activityId,
            outcome = outcome,
            streamTypesAvailable = mapped.availableTypes.size,
            streamTypesImported = mapped.importedTypes.size,
            missingStreamTypes = mapped.missingTypes.joinToString(",").take(400).ifEmpty { null },
            sampleCount = written,
            sampleSpanSeconds = mapped.spanSeconds,
            qualityFlags = quality.joinToString(",").take(400).ifEmpty { null },
            importedAt = attemptedAt,
        )
    }

    // ---- duplicate detection ----

    private sealed interface DuplicateSearch {
        data object None : DuplicateSearch
        data class Found(val activity: Activity, val method: MatchMethod, val evidence: MatchEvidence) : DuplicateSearch
        data class Ambiguous(val count: Int) : DuplicateSearch
    }

    /**
     * Whether a stored activity already IS this workout.
     *
     * Uses [IntervalsActivityMatcher] unchanged, one stored activity at a time: identity first (the
     * payload naming an upstream id), then the live-measured composite tolerances on type, start, moving
     * duration and distance. Several independent signals have to agree, and if two stored activities
     * both agree, nothing is merged - an ambiguous identity is reported, not resolved.
     */
    private fun findDuplicate(
        candidate: IntervalsImportCandidate,
        snapshot: IntervalsActivitySnapshot,
        zone: ZoneId,
    ): DuplicateSearch {
        val startedAt = candidate.startDate ?: return DuplicateSearch.None
        val localDate = startedAt.atZone(zone).toLocalDate()
        val from = localDate.minusDays(1).atStartOfDay(zone).toInstant()
        val toExclusive = localDate.plusDays(2).atStartOfDay(zone).toInstant()
        val athleteId = athleteService.defaultAthlete.id
        val matches = activities.findByAthleteIdAndStartedAtGreaterThanEqualAndStartedAtLessThan(athleteId, from, toExclusive)
            // The row this very Intervals activity was imported into is not a duplicate of itself.
            .filter { !(it.externalSource == ExternalSource.INTERVALS_ICU && it.externalId == candidate.intervalsActivityId) }
            .mapNotNull { stored ->
                when (val result = matcher.match(stored, listOf(snapshot))) {
                    is MatchResult.Matched -> Triple(stored, result.method, result.evidence)
                    else -> null
                }
            }
        return when (matches.size) {
            0 -> DuplicateSearch.None
            1 -> matches[0].let { DuplicateSearch.Found(it.first, it.second, it.third) }
            else -> DuplicateSearch.Ambiguous(matches.size)
        }
    }

    private fun link(
        athleteId: Long,
        activity: Activity,
        snapshot: IntervalsActivitySnapshot,
        base: IntervalsImportRecord,
        method: MatchMethod,
        evidence: MatchEvidence,
        dryRun: Boolean,
    ): IntervalsImportRecord {
        val existing = enrichmentStore.findLink(activity.id, ExternalSource.INTERVALS_ICU)
        if (existing?.externalActivityId == snapshot.intervalsActivityId) {
            return persist(athleteId, base.copy(
                activityId = activity.id,
                outcome = IntervalsImportOutcome.SKIPPED_ALREADY_LINKED,
                reasonCode = "LINK_PRESENT",
            ), dryRun, 0)
        }
        if (dryRun) {
            return base.copy(
                activityId = activity.id,
                outcome = IntervalsImportOutcome.LINKED_EXISTING,
                reasonCode = "DRY_RUN_${method.name}",
            )
        }
        val now = Instant.now(clock)
        return try {
            enrichmentStore.upsertLink(activity.id, ExternalSource.INTERVALS_ICU, snapshot.intervalsActivityId, method, evidence, now)
            enrichmentStore.upsertMetrics(activity.id, snapshot, now)
            importStore.record(athleteId, base.copy(
                activityId = activity.id,
                outcome = IntervalsImportOutcome.LINKED_EXISTING,
                reasonCode = "MATCHED_${method.name}",
                importedAt = now,
            ), 1)
        } catch (e: SourceLinkConflictException) {
            // That Intervals activity already belongs to a different RunningAI activity. Re-pointing it
            // automatically is exactly the silent merge this importer must not do.
            importStore.record(athleteId, base.copy(
                activityId = activity.id,
                outcome = IntervalsImportOutcome.FAILED,
                reasonCode = "INTERVALS_LINK_CONFLICT",
                lastErrorCode = "INTERVALS_LINK_CONFLICT",
            ), 1)
        }
    }

    // ---- plumbing ----

    private fun persist(
        athleteId: Long,
        record: IntervalsImportRecord,
        dryRun: Boolean,
        attemptDelta: Int,
    ): IntervalsImportRecord = if (dryRun) record else importStore.record(athleteId, record, attemptDelta)

    private fun baseRecord(candidate: IntervalsImportCandidate) = IntervalsImportRecord(
        intervalsActivityId = candidate.intervalsActivityId,
        activityId = null,
        outcome = IntervalsImportOutcome.FAILED,
        reasonCode = null,
        sourceLabel = candidate.source,
        sourceExternalId = candidate.externalId,
        stravaActivityId = candidate.stravaActivityId,
        deviceName = candidate.deviceName,
        activityTypeLabel = candidate.type,
        startedAt = candidate.startDate,
        startDateLocal = candidate.startDateLocal,
        elapsedTimeSeconds = candidate.elapsedTimeSeconds,
        movingTimeSeconds = candidate.movingTimeSeconds,
        distanceMeters = candidate.distanceMeters,
        sourceLapCount = candidate.lapCount,
        streamTypesAvailable = null,
        streamTypesImported = null,
        missingStreamTypes = null,
        sampleCount = null,
        sampleSpanSeconds = null,
        qualityFlags = null,
        sourceAnalyzedAt = candidate.analyzedAt,
        attemptCount = 0,
        lastErrorCode = null,
        importedAt = null,
    )

    private fun failureRecord(candidate: IntervalsImportCandidate, code: String) =
        baseRecord(candidate).copy(outcome = IntervalsImportOutcome.FAILED, reasonCode = code, lastErrorCode = code)

    private fun summarize(
        oldest: LocalDate,
        newest: LocalDate,
        dryRun: Boolean,
        seen: Int,
        unmappable: Int,
        stopReason: String?,
        results: List<IntervalsImportRecord>,
    ) = IntervalsActivityImportResult(
        oldest = oldest,
        newest = newest,
        dryRun = dryRun,
        candidatesSeen = seen,
        candidatesUnmappable = unmappable,
        processed = results.size,
        imported = results.count { it.outcome == IntervalsImportOutcome.IMPORTED },
        refreshed = results.count { it.outcome == IntervalsImportOutcome.REFRESHED },
        alreadyImported = results.count { it.outcome == IntervalsImportOutcome.SKIPPED_ALREADY_IMPORTED },
        linkedExisting = results.count { it.outcome == IntervalsImportOutcome.LINKED_EXISTING },
        alreadyLinked = results.count { it.outcome == IntervalsImportOutcome.SKIPPED_ALREADY_LINKED },
        skippedGarminSourced = results.count { it.outcome == IntervalsImportOutcome.SKIPPED_GARMIN_SOURCED },
        skippedAmbiguous = results.count { it.outcome == IntervalsImportOutcome.SKIPPED_AMBIGUOUS },
        skippedUnsupportedType = results.count { it.outcome == IntervalsImportOutcome.SKIPPED_UNSUPPORTED_TYPE },
        skippedNoSourceData = results.count { it.outcome == IntervalsImportOutcome.SKIPPED_NO_SOURCE_DATA },
        failed = results.count { it.outcome == IntervalsImportOutcome.FAILED },
        samplesWritten = results.sumOf { it.sampleCount ?: 0 },
        stopReason = stopReason,
        items = results,
    )

    private fun requireSafeRuntime() {
        val unsafe = SAFETY_FLAGS.filter { environment.getProperty(it, Boolean::class.java, false) }
        if (unsafe.isNotEmpty()) throw UnsafeIntervalsImportRuntimeException(unsafe)
    }

    private fun sleep(delay: Duration) {
        if (!delay.isZero && !delay.isNegative) Thread.sleep(delay.toMillis())
    }

    private companion object {
        /**
         * Reasons that mean the next request would fail the same way, so the run stops instead of
         * walking the rest of the list into the same wall.
         */
        val STOP_REASONS = setOf(
            IntervalsException.Reason.NOT_CONFIGURED,
            IntervalsException.Reason.AUTH_FAILED,
            IntervalsException.Reason.FORBIDDEN,
            IntervalsException.Reason.RATE_LIMITED,
            IntervalsException.Reason.CONNECTION_FAILED,
            IntervalsException.Reason.TIMEOUT,
        )

        /**
         * The importer writes nothing outside this database, so the question it has to answer before
         * running is narrower than the historical backfill's: could something ELSE be publishing while
         * tens of thousands of rows appear? These are the switches that make that possible.
         *
         * `running-ai.mcp.enabled` is deliberately not here: the MCP server's only write tool reaches
         * Intervals.icu through `running-ai.workout-publishing.enabled`, which is in this list, so the
         * publishing path is already covered - and the read-only MCP tools must stay usable for
         * verifying what an import produced.
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
