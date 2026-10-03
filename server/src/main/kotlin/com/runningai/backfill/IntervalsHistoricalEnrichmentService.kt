package com.runningai.backfill

import com.runningai.activity.ActivityRepository
import com.runningai.activity.ExternalSource
import com.runningai.enrichment.IntervalsActivitySnapshot
import com.runningai.enrichment.IntervalsEnrichmentStore
import com.runningai.enrichment.IntervalsPayloadType
import com.runningai.enrichment.MatchResult
import com.runningai.enrichment.SourceLinkConflictException
import com.runningai.integration.intervals.IntervalsActivityMapper
import com.runningai.integration.intervals.IntervalsActivityMatcher
import com.runningai.integration.intervals.IntervalsException
import com.runningai.integration.intervals.IntervalsMappingException
import com.runningai.integration.intervals.IntervalsReadClient
import com.runningai.integration.intervals.IntervalsWellnessMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Intervals.icu side of the historical backfill (phases INTERVALS_ACTIVITIES and INTERVALS_FITNESS).
 * Read-only towards Intervals.icu, with a hard call shape (§29/§30/§36):
 *
 *  - activity lists only, in deterministic non-overlapping windows of at most [MAX_WINDOW_DAYS] days
 *    (~3 GETs for 90 days); NEVER a per-activity detail GET, never `intervals=true`
 *  - wellness as ONE GET over the whole window (this path is separate from the manual enrichment
 *    endpoint, whose 31-day guard stays untouched)
 *  - sequential, no retry; any transport/auth/rate-limit failure pauses the run
 *
 * Every list item is stored raw-first (V18 `intervals_raw_payload`), then matching happens entirely
 * locally with the 6H-5 matcher. UNMATCHED / AMBIGUOUS are recorded per activity and never stop the
 * run (Intervals is a secondary source); a source-link conflict is a data-integrity problem and pauses.
 */
@Service
class IntervalsHistoricalEnrichmentService(
    private val readClient: IntervalsReadClient,
    private val activityMapper: IntervalsActivityMapper,
    private val wellnessMapper: IntervalsWellnessMapper,
    private val matcher: IntervalsActivityMatcher,
    private val enrichmentStore: IntervalsEnrichmentStore,
    private val backfillStore: HistoricalBackfillStore,
    private val activities: ActivityRepository,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(IntervalsHistoricalEnrichmentService::class.java)

    data class ActivityEnrichmentCounts(val listCalls: Int, val snapshots: Int, val unmapped: Int)

    /** Fetches the window's Intervals activities (raw-first) and matches every backfill target locally. */
    fun enrichActivities(run: HistoricalBackfillRunEntity, zone: ZoneId): ActivityEnrichmentCounts {
        val runId = requireNotNull(run.id)
        var unmapped = 0
        val snapshots = mutableListOf<IntervalsActivitySnapshot>()
        val windows = windows(run.startDate, run.endDate)
        for ((oldest, newest) in windows) {
            val body = call { readClient.listActivities(oldest, newest) }.body()
            if (!body.isArray) {
                throw BackfillPauseException("INTERVALS_INVALID_RESPONSE", "Intervals activities response is not an array")
            }
            body.forEach { item ->
                val snapshot = try {
                    activityMapper.map(item)
                } catch (e: IntervalsMappingException) {
                    unmapped++
                    log.warn("Intervals backfill item skipped: code={}", e.code)
                    return@forEach
                }
                // Raw-first for EVERY item of the window, matched or not: a later mapper can reprocess.
                enrichmentStore.saveOrReplaceRaw(
                    run.athleteId, IntervalsPayloadType.ACTIVITY, snapshot.intervalsActivityId,
                    snapshot.startDate?.atZone(zone)?.toLocalDate(), item, Instant.now(clock),
                )
                snapshots += snapshot
            }
        }
        matchAll(runId, snapshots)
        log.info("Intervals historical activities done: runId={} windows={} snapshots={} unmapped={}",
            runId, windows.size, snapshots.size, unmapped)
        return ActivityEnrichmentCounts(windows.size, snapshots.size, unmapped)
    }

    private fun matchAll(runId: Long, snapshots: List<IntervalsActivitySnapshot>) {
        backfillStore.itemsOf(runId)
            .filter { it.summaryStatus != BackfillSummaryStatus.SKIPPED_UNSUPPORTED }
            .forEach { item ->
                val activityId = item.activityId ?: return@forEach
                val activity = activities.findById(activityId).orElse(null) ?: return@forEach
                when (val match = matcher.match(activity, snapshots)) {
                    is MatchResult.Matched -> {
                        try {
                            enrichmentStore.upsertLink(activityId, ExternalSource.INTERVALS_ICU,
                                match.snapshot.intervalsActivityId, match.method, match.evidence, Instant.now(clock))
                            enrichmentStore.upsertMetrics(activityId, match.snapshot, Instant.now(clock))
                        } catch (e: SourceLinkConflictException) {
                            backfillStore.updateItem(requireNotNull(item.id)) { it.lastErrorCode = "INTERVALS_LINK_CONFLICT" }
                            throw BackfillPauseException("INTERVALS_LINK_CONFLICT", e.message ?: "source link conflict")
                        }
                        backfillStore.updateItem(requireNotNull(item.id)) { it.intervalsStatus = BackfillIntervalsStatus.MATCHED }
                    }
                    is MatchResult.Unmatched ->
                        backfillStore.updateItem(requireNotNull(item.id)) { it.intervalsStatus = BackfillIntervalsStatus.UNMATCHED }
                    is MatchResult.Ambiguous ->
                        backfillStore.updateItem(requireNotNull(item.id)) { it.intervalsStatus = BackfillIntervalsStatus.AMBIGUOUS }
                }
            }
    }

    data class FitnessCounts(val daysStored: Int, val failedDays: Int)

    /** One wellness GET over the whole window; each returned day is stored raw-first and normalised. */
    fun enrichFitness(run: HistoricalBackfillRunEntity): FitnessCounts {
        val body = call { readClient.getWellness(run.startDate, run.endDate) }.body()
        if (!body.isArray) {
            throw BackfillPauseException("INTERVALS_INVALID_RESPONSE", "Intervals wellness response is not an array")
        }
        var stored = 0
        var failed = 0
        body.forEach { entry ->
            val id = entry.get("id")?.takeIf { it.isTextual }?.asText()
            if (id == null) {
                failed++
                return@forEach
            }
            val effectiveDate = runCatching { LocalDate.parse(id) }.getOrNull()
            enrichmentStore.saveOrReplaceRaw(
                run.athleteId, IntervalsPayloadType.WELLNESS_DAY, id, effectiveDate, entry, Instant.now(clock))
            try {
                enrichmentStore.upsertFitnessDay(run.athleteId, wellnessMapper.map(entry), Instant.now(clock))
                stored++
            } catch (e: IntervalsMappingException) {
                failed++
                log.warn("Intervals wellness day failed in backfill: date={} code={} (raw preserved)", id, e.code)
            }
        }
        log.info("Intervals fitness backfill done: window={}..{} stored={} failed={}", run.startDate, run.endDate, stored, failed)
        return FitnessCounts(stored, failed)
    }

    private fun <T> call(block: () -> T): T = try {
        block()
    } catch (e: IntervalsException) {
        throw BackfillPauseException("INTERVALS_${e.reason.name}", "Intervals backfill stopped: ${e.reason}")
    }

    companion object {
        const val MAX_WINDOW_DAYS = 31L

        /** Deterministic, non-overlapping, gap-free windows of at most [MAX_WINDOW_DAYS] days each. */
        fun windows(start: LocalDate, end: LocalDate): List<Pair<LocalDate, LocalDate>> {
            require(!end.isBefore(start)) { "end must not be before start" }
            val result = mutableListOf<Pair<LocalDate, LocalDate>>()
            var from = start
            while (!from.isAfter(end)) {
                val to = minOf(from.plusDays(MAX_WINDOW_DAYS - 1), end)
                result += from to to
                from = to.plusDays(1)
            }
            return result
        }
    }
}
