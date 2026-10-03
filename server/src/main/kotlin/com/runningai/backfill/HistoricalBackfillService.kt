package com.runningai.backfill

import com.runningai.activity.ActivityRepository
import com.runningai.activity.ExternalSource
import com.runningai.activity.detail.ActivityDetailStore
import com.runningai.activity.detail.DetailCollectionOutcome
import com.runningai.activity.detail.DetailPartStatus
import com.runningai.activity.detail.DetailPayloadType
import com.runningai.activity.detail.SampleCompleteness
import com.runningai.analysis.RUNNING_ANALYSIS_VERSION
import com.runningai.analysis.RunningActivityAnalysisService
import com.runningai.athlete.AthleteService
import com.runningai.common.exception.ResourceNotFoundException
import com.runningai.enrichment.IntervalsEnrichmentStore
import com.runningai.integration.garmin.GarminActivityDetailIngestionService
import com.runningai.integration.garmin.GarminConnectorException
import com.runningai.integration.garmin.GarminRecoverySyncProperties
import com.runningai.integration.garmin.GarminRecoverySyncService
import org.slf4j.LoggerFactory
import org.springframework.core.env.Environment
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

/** Another historical backfill is already executing in this JVM, or a RUNNING run exists in the DB. */
class HistoricalBackfillAlreadyRunningException(message: String) : RuntimeException(message)

/** The runtime is not in the safe state historical backfill requires (§46). */
class UnsafeBackfillRuntimeException(val unsafeFlags: List<String>) :
    RuntimeException("Historical backfill refused: unsafe runtime flags enabled: $unsafeFlags")

/** Resume was requested on a run whose status does not allow it. */
class BackfillNotResumableException(val code: String, message: String) : RuntimeException(message)

data class HistoricalBackfillRequest(val endDate: LocalDate? = null, val days: Int? = null)

data class HistoricalBackfillResponse(
    val runId: Long,
    val status: BackfillRunStatus,
    val phase: BackfillPhase,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val targetActivities: Int?,
    val completedActivities: Int,
    val skippedUnsupported: Int,
    val detailRefetched: Int,
    val detailSkippedAlreadyComplete: Int,
    val detailFull: Int,
    val detailNoSamples: Int,
    val analysisComplete: Int,
    val analysisPartial: Int,
    val intervalsMatched: Int,
    val intervalsUnmatched: Int,
    val intervalsAmbiguous: Int,
    val fitnessDays: Int?,
    val recoveryDays: Int,
    val stoppedAt: Instant?,
    val stopReason: String?,
)

/**
 * Manual 90-day historical backfill (Phase 6H-6). Explicit trigger only - no scheduler, no startup
 * run, no background executor: the whole pipeline runs synchronously on the calling thread, phase by
 * phase, committing a persistent checkpoint after every unit of work so a PAUSED run resumes without
 * repeating finished Garmin/Intervals calls.
 *
 *   GARMIN_DISCOVERY -> GARMIN_DETAIL_ANALYSIS -> INTERVALS_ACTIVITIES -> INTERVALS_FITNESS
 *   -> GARMIN_RECOVERY -> VERIFY -> COMPLETED
 *
 * Fail-stop discipline: no call is ever retried; a Garmin/Intervals account-level failure (401/403/
 * 429/unreachable), a detail-integrity failure, a sample-fidelity failure (anything but FULL or a
 * legitimately EMPTY stream) or an analysis failure PAUSES the run where it stands. An unexpected
 * exception marks it FAILED (never auto-resumable). `garmin_sync_state` is never read or written:
 * incremental sync keeps its own checkpoint untouched.
 */
@Service
class HistoricalBackfillService(
    private val store: HistoricalBackfillStore,
    private val discovery: GarminHistoricalActivityDiscoveryService,
    private val detailIngestion: GarminActivityDetailIngestionService,
    private val detailStore: ActivityDetailStore,
    private val analysisService: RunningActivityAnalysisService,
    private val intervalsEnrichment: IntervalsHistoricalEnrichmentService,
    private val enrichmentStore: IntervalsEnrichmentStore,
    private val recoverySync: GarminRecoverySyncService,
    private val recoveryProperties: GarminRecoverySyncProperties,
    private val activities: ActivityRepository,
    private val athleteService: AthleteService,
    private val properties: HistoricalBackfillProperties,
    private val environment: Environment,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(HistoricalBackfillService::class.java)
    private val inFlight = AtomicBoolean(false)

    fun start(request: HistoricalBackfillRequest): HistoricalBackfillResponse {
        requireSafeRuntime()
        val athlete = athleteService.defaultAthlete
        val zone = ZoneId.of(athlete.timezone)
        val days = request.days ?: DEFAULT_DAYS
        require(days in 1..MAX_DAYS) { "days must be between 1 and $MAX_DAYS" }
        val endDate = request.endDate ?: LocalDate.now(clock.withZone(zone))
        val startDate = endDate.minusDays(days - 1L)
        return locked(athlete.id) {
            val run = store.createRun(athlete.id, startDate, endDate, days, Instant.now(clock))
            log.info("Historical backfill started: runId={} window={}..{} days={}", run.id, startDate, endDate, days)
            execute(requireNotNull(run.id), zone)
        }
    }

    fun resume(runId: Long): HistoricalBackfillResponse {
        val run = store.run(runId)
            ?: throw ResourceNotFoundException("HISTORICAL_BACKFILL_NOT_FOUND", "No backfill run $runId")
        when (run.status) {
            BackfillRunStatus.COMPLETED ->
                // Deterministic refusal, zero network calls: a finished run has nothing to resume (§50).
                throw BackfillNotResumableException("HISTORICAL_BACKFILL_ALREADY_COMPLETED",
                    "Backfill run $runId is COMPLETED; nothing to resume")
            BackfillRunStatus.FAILED ->
                throw BackfillNotResumableException("HISTORICAL_BACKFILL_FAILED_NOT_RESUMABLE",
                    "Backfill run $runId FAILED; investigate before starting a new run")
            BackfillRunStatus.RUNNING ->
                throw HistoricalBackfillAlreadyRunningException("Backfill run $runId is already RUNNING")
            BackfillRunStatus.PAUSED -> Unit
        }
        requireSafeRuntime()
        val zone = ZoneId.of(athleteService.defaultAthlete.timezone)
        return locked(run.athleteId) {
            store.updateRun(runId) {
                it.status = BackfillRunStatus.RUNNING
                it.stopReason = null
            }
            log.info("Historical backfill resumed: runId={} phase={}", runId, run.currentPhase)
            execute(runId, zone)
        }
    }

    fun status(runId: Long): HistoricalBackfillResponse {
        val run = store.run(runId)
            ?: throw ResourceNotFoundException("HISTORICAL_BACKFILL_NOT_FOUND", "No backfill run $runId")
        return response(run)
    }

    // ---- execution ----

    private fun execute(runId: Long, zone: ZoneId): HistoricalBackfillResponse {
        try {
            var run = requireNotNull(store.run(runId))
            if (run.currentPhase == BackfillPhase.GARMIN_DISCOVERY) {
                // Always from offset 0, even on resume: offsets drift when new activities appear (§14).
                val result = discovery.discover(run, zone)
                run = store.updateRun(runId) {
                    it.discoveryComplete = true
                    it.targetActivityCount = result.targetActivities
                    it.currentPhase = BackfillPhase.GARMIN_DETAIL_ANALYSIS
                }
                log.info("Backfill discovery: runId={} pages={} targets={}", runId, result.pages, result.targetActivities)
            }
            if (run.currentPhase == BackfillPhase.GARMIN_DETAIL_ANALYSIS) {
                detailAndAnalysis(runId)
                run = store.updateRun(runId) { it.currentPhase = BackfillPhase.INTERVALS_ACTIVITIES }
            }
            if (run.currentPhase == BackfillPhase.INTERVALS_ACTIVITIES) {
                intervalsEnrichment.enrichActivities(run, zone)
                run = store.updateRun(runId) { it.currentPhase = BackfillPhase.INTERVALS_FITNESS }
            }
            if (run.currentPhase == BackfillPhase.INTERVALS_FITNESS) {
                val fitness = intervalsEnrichment.enrichFitness(run)
                run = store.updateRun(runId) {
                    it.fitnessDayCount = fitness.daysStored
                    it.currentPhase = BackfillPhase.GARMIN_RECOVERY
                }
            }
            if (run.currentPhase == BackfillPhase.GARMIN_RECOVERY) {
                recovery(runId, run.endDate)
                run = store.updateRun(runId) { it.currentPhase = BackfillPhase.VERIFY }
            }
            if (run.currentPhase == BackfillPhase.VERIFY) {
                verify(run, zone)
                run = store.updateRun(runId) {
                    it.currentPhase = BackfillPhase.COMPLETED
                    it.status = BackfillRunStatus.COMPLETED
                    it.completedAt = Instant.now(clock)
                }
                log.info("Historical backfill completed: runId={}", runId)
            }
            return response(run)
        } catch (e: BackfillPauseException) {
            val paused = store.updateRun(runId) {
                it.status = BackfillRunStatus.PAUSED
                it.stopReason = e.reason
            }
            log.warn("Historical backfill paused: runId={} phase={} reason={}", runId, paused.currentPhase, e.reason)
            return response(paused)
        } catch (e: Exception) {
            store.updateRun(runId) {
                it.status = BackfillRunStatus.FAILED
                it.stopReason = "INTERNAL_${e.javaClass.simpleName}".take(64)
            }
            log.error("Historical backfill failed: runId={}", runId, e)
            throw e
        }
    }

    /** Oldest -> newest; one committed checkpoint per activity; a Garmin delay only between real fetches. */
    private fun detailAndAnalysis(runId: Long) {
        var garminTouched = false
        store.itemsOf(runId)
            .filter { it.summaryStatus != BackfillSummaryStatus.SKIPPED_UNSUPPORTED }
            .sortedBy { it.ordinal }
            .forEach { item ->
                if (item.garminStagesComplete()) return@forEach
                val itemId = requireNotNull(item.id)
                val activityId = item.activityId
                    ?: throw BackfillPauseException("BACKFILL_ITEM_WITHOUT_ACTIVITY",
                        "Supported backfill item has no activity id")

                var detailChanged = false
                if (item.detailStatus == null) {
                    val existing = storedDetailOutcome(activityId)
                    if (existing != null) {
                        store.updateItem(itemId) {
                            it.detailStatus = BackfillDetailStatus.SKIPPED_ALREADY_COMPLETE
                            it.sampleOutcome = existing
                        }
                    } else {
                        if (garminTouched) sleep(properties.activityDelay)
                        garminTouched = true
                        val outcome = collectDetail(itemId, item.garminExternalId)
                        store.updateItem(itemId) {
                            it.detailStatus = BackfillDetailStatus.COLLECTED
                            it.sampleOutcome = outcome
                            it.lastErrorCode = null
                        }
                        detailChanged = true
                    }
                } else {
                    detailChanged = item.detailStatus == BackfillDetailStatus.COLLECTED && item.analysisStatus == null
                }

                val existingAnalysis = analysisService.find(activityId)
                val analysis = if (!detailChanged && existingAnalysis?.analysisVersion == RUNNING_ANALYSIS_VERSION) {
                    existingAnalysis
                } else {
                    try {
                        analysisService.analyse(activityId)
                    } catch (e: Exception) {
                        store.updateItem(itemId) { it.lastErrorCode = "ANALYSIS_FAILED" }
                        throw BackfillPauseException("ANALYSIS_FAILED",
                            "Analysis failed for a backfill activity: ${e.javaClass.simpleName}")
                    }
                }
                store.updateItem(itemId) { it.analysisStatus = analysis.status.name }
                store.updateRun(runId) { it.completedActivityCount = it.completedActivityCount + 1 }
            }
    }

    /** Fetches all detail parts and applies the integrity + sample-fidelity gates (§21/§22/§23). */
    private fun collectDetail(itemId: Long, garminExternalId: String): BackfillSampleOutcome {
        val result = try {
            detailIngestion.collect(garminExternalId)
        } catch (e: GarminConnectorException) {
            store.updateItem(itemId) { it.lastErrorCode = "GARMIN_${e.reason.name}" }
            throw BackfillPauseException("GARMIN_${e.reason.name}", "Garmin detail stage stopped: ${e.reason}")
        }
        if (result.outcome != DetailCollectionOutcome.COMPLETE) {
            val firstError = result.parts.firstOrNull { it.errorCode != null }?.errorCode
            store.updateItem(itemId) { it.lastErrorCode = firstError ?: "DETAIL_${result.outcome.name}" }
            throw BackfillPauseException("DETAIL_COLLECTION_${result.outcome.name}",
                "Detail collection did not COMPLETE (${firstError ?: result.outcome})")
        }
        val stream = result.parts.first { it.payloadType == DetailPayloadType.ACTIVITY_DETAILS_STREAM }
        return when {
            stream.status == DetailPartStatus.EMPTY -> BackfillSampleOutcome.NO_SAMPLE_STREAM
            stream.sampleCompleteness == SampleCompleteness.FULL -> BackfillSampleOutcome.FULL
            else -> {
                val reason = if (stream.sampleCompleteness == SampleCompleteness.DOWNSAMPLED) {
                    "SAMPLE_STREAM_DOWNSAMPLED"
                } else {
                    "SAMPLE_STREAM_FIDELITY_UNKNOWN"
                }
                store.updateItem(itemId) { it.lastErrorCode = reason }
                // Never retried at another maxChartSize: the operator reviews the setting, then resumes.
                throw BackfillPauseException(reason, "Sample stream fidelity gate failed: $reason")
            }
        }
    }

    /**
     * Whether the stored detail already passes every gate (§20), and with which sample outcome.
     * DOWNSAMPLED or UNKNOWN streams never pass: they are exactly what this run must refetch.
     */
    private fun storedDetailOutcome(activityId: Long): BackfillSampleOutcome? {
        val parts = detailStore.collection(activityId).associateBy { it.payloadType }
        if (!REQUIRED_PARTS.all { it in parts }) return null
        if (parts.values.any { it.status == DetailPartStatus.FETCH_FAILED || it.status == DetailPartStatus.MAPPING_FAILED }) return null
        val stream = parts.getValue(DetailPayloadType.ACTIVITY_DETAILS_STREAM)
        return when {
            stream.status == DetailPartStatus.EMPTY -> BackfillSampleOutcome.NO_SAMPLE_STREAM
            stream.status == DetailPartStatus.NORMALIZED &&
                stream.sampleFidelity?.completeness == SampleCompleteness.FULL -> BackfillSampleOutcome.FULL
            else -> null
        }
    }

    /** 28 days ending on the run's end date, newest first, persisted cursor, no re-fetch of done days. */
    private fun recovery(runId: Long, endDate: LocalDate) {
        val recoveryStart = endDate.minusDays(RECOVERY_DAYS - 1L)
        var cursor = store.run(runId)?.nextRecoveryDate ?: endDate
        var firstCall = true
        while (!cursor.isBefore(recoveryStart)) {
            if (!firstCall) sleep(recoveryProperties.backfillDelay())
            try {
                recoverySync.syncDay(cursor)
            } catch (e: GarminConnectorException) {
                throw BackfillPauseException("GARMIN_${e.reason.name}", "Recovery stage stopped at $cursor: ${e.reason}")
            }
            firstCall = false
            val next = cursor.minusDays(1)
            store.updateRun(runId) {
                it.nextRecoveryDate = next
                it.recoveryDayCount = it.recoveryDayCount + 1
            }
            cursor = next
        }
    }

    /** Local-only verification (§53-§55): no network, pause with a specific reason on any gap. */
    private fun verify(run: HistoricalBackfillRunEntity, zone: ZoneId) {
        val runId = requireNotNull(run.id)
        store.itemsOf(runId).forEach { item ->
            if (item.summaryStatus == BackfillSummaryStatus.SKIPPED_UNSUPPORTED) return@forEach
            val activityId = item.activityId
                ?: throw BackfillPauseException("VERIFY_ACTIVITY_MISSING", "A supported item has no activity row")
            if (!activities.existsById(activityId)) {
                throw BackfillPauseException("VERIFY_ACTIVITY_MISSING", "Activity row disappeared")
            }
            if (storedDetailOutcome(activityId) == null) {
                throw BackfillPauseException("VERIFY_DETAIL_INCOMPLETE", "Stored detail fails the gates")
            }
            val analysis = analysisService.find(activityId)
                ?: throw BackfillPauseException("VERIFY_ANALYSIS_MISSING", "No stored analysis")
            if (analysis.analysisVersion != RUNNING_ANALYSIS_VERSION) {
                throw BackfillPauseException("VERIFY_ANALYSIS_VERSION", "Analysis is not $RUNNING_ANALYSIS_VERSION")
            }
            when (item.intervalsStatus) {
                null -> throw BackfillPauseException("VERIFY_INTERVALS_STATUS_MISSING", "Intervals matching never ran")
                BackfillIntervalsStatus.MATCHED -> {
                    if (enrichmentStore.findLink(activityId, ExternalSource.INTERVALS_ICU) == null ||
                        enrichmentStore.findMetrics(activityId) == null
                    ) {
                        throw BackfillPauseException("VERIFY_INTERVALS_MISSING", "Matched activity lacks link/metrics")
                    }
                }
                else -> Unit // UNMATCHED / AMBIGUOUS are explicit, legitimate end states
            }
        }
        // No DOWNSAMPLED or UNKNOWN stream may remain on ANY window activity (§54/§55).
        val from = run.startDate.atStartOfDay(zone).toInstant()
        val toExclusive = run.endDate.plusDays(1).atStartOfDay(zone).toInstant()
        activities.findByAthleteIdAndStartedAtGreaterThanEqualAndStartedAtLessThan(run.athleteId, from, toExclusive)
            .forEach { activity ->
                val record = detailStore.part(activity.id, DetailPayloadType.ACTIVITY_DETAILS_STREAM) ?: return@forEach
                // A legitimately EMPTY stream has nothing to be complete about; only a stored stream
                // must prove it is FULL.
                if (record.status == DetailPartStatus.EMPTY) return@forEach
                when (record.sampleFidelity?.completeness) {
                    SampleCompleteness.FULL -> Unit
                    SampleCompleteness.DOWNSAMPLED ->
                        throw BackfillPauseException("VERIFY_DOWNSAMPLED_REMAINING", "A window activity is still DOWNSAMPLED")
                    else ->
                        throw BackfillPauseException("VERIFY_FIDELITY_UNKNOWN", "A window activity has UNKNOWN fidelity")
                }
            }
        if (run.fitnessDayCount == null) {
            throw BackfillPauseException("VERIFY_FITNESS_MISSING", "The fitness phase recorded no result")
        }
        val recoveryDone = run.nextRecoveryDate?.isBefore(run.endDate.minusDays(RECOVERY_DAYS - 1L)) == true
        if (!recoveryDone) {
            throw BackfillPauseException("VERIFY_RECOVERY_INCOMPLETE", "The recovery cursor did not finish its window")
        }
    }

    // ---- plumbing ----

    private fun <T> locked(athleteId: Long, body: () -> T): T {
        if (!inFlight.compareAndSet(false, true)) {
            throw HistoricalBackfillAlreadyRunningException("A historical backfill is already running in this JVM")
        }
        try {
            if (store.hasRunningRun(athleteId)) {
                throw HistoricalBackfillAlreadyRunningException("A RUNNING backfill already exists for this athlete")
            }
            return body()
        } finally {
            inFlight.set(false)
        }
    }

    private fun requireSafeRuntime() {
        val unsafe = SAFETY_FLAGS.filter { environment.getProperty(it, Boolean::class.java, false) }
        if (unsafe.isNotEmpty()) throw UnsafeBackfillRuntimeException(unsafe)
    }

    private fun sleep(delay: Duration) {
        if (!delay.isZero && !delay.isNegative) Thread.sleep(delay.toMillis())
    }

    private fun response(run: HistoricalBackfillRunEntity): HistoricalBackfillResponse {
        val items = store.itemsOf(requireNotNull(run.id))
        val stopped = run.status == BackfillRunStatus.PAUSED || run.status == BackfillRunStatus.FAILED
        return HistoricalBackfillResponse(
            runId = requireNotNull(run.id),
            status = run.status,
            phase = run.currentPhase,
            startDate = run.startDate,
            endDate = run.endDate,
            targetActivities = run.targetActivityCount,
            completedActivities = run.completedActivityCount,
            skippedUnsupported = items.count { it.summaryStatus == BackfillSummaryStatus.SKIPPED_UNSUPPORTED },
            detailRefetched = items.count { it.detailStatus == BackfillDetailStatus.COLLECTED },
            detailSkippedAlreadyComplete = items.count { it.detailStatus == BackfillDetailStatus.SKIPPED_ALREADY_COMPLETE },
            detailFull = items.count { it.sampleOutcome == BackfillSampleOutcome.FULL },
            detailNoSamples = items.count { it.sampleOutcome == BackfillSampleOutcome.NO_SAMPLE_STREAM },
            analysisComplete = items.count { it.analysisStatus == "COMPLETE" },
            analysisPartial = items.count { it.analysisStatus == "PARTIAL" },
            intervalsMatched = items.count { it.intervalsStatus == BackfillIntervalsStatus.MATCHED },
            intervalsUnmatched = items.count { it.intervalsStatus == BackfillIntervalsStatus.UNMATCHED },
            intervalsAmbiguous = items.count { it.intervalsStatus == BackfillIntervalsStatus.AMBIGUOUS },
            fitnessDays = run.fitnessDayCount,
            recoveryDays = run.recoveryDayCount,
            stoppedAt = if (stopped) run.updatedAt else null,
            stopReason = run.stopReason,
        )
    }

    companion object {
        const val DEFAULT_DAYS = 90
        const val MAX_DAYS = 90
        const val RECOVERY_DAYS = 28

        val REQUIRED_PARTS = setOf(
            DetailPayloadType.ACTIVITY_LIST,
            DetailPayloadType.ACTIVITY_DETAIL,
            DetailPayloadType.SPLITS,
            DetailPayloadType.HR_ZONES,
            DetailPayloadType.POWER_ZONES,
            DetailPayloadType.ACTIVITY_DETAILS_STREAM,
        )

        /** Every one of these must be false before a backfill may start or resume (§46). */
        val SAFETY_FLAGS = listOf(
            "running-ai.workout-publishing.enabled",
            "running-ai.workout-publishing.scheduler.enabled",
            "running-ai.draft-publishing.enabled",
            "running-ai.mcp.enabled",
            "running-ai.garmin.scheduler.enabled",
            "running-ai.garmin.profile-sync.scheduler.enabled",
        )
    }
}
