package com.runningai.integration.intervals

import com.fasterxml.jackson.databind.JsonNode
import com.runningai.activity.Activity
import com.runningai.activity.ActivityRepository
import com.runningai.activity.ExternalSource
import com.runningai.athlete.AthleteRepository
import com.runningai.athlete.AthleteService
import com.runningai.common.exception.ResourceNotFoundException
import com.runningai.enrichment.ActivityIntervalsMetricsData
import com.runningai.enrichment.ActivitySourceLinkData
import com.runningai.enrichment.IntervalsActivitySnapshot
import com.runningai.enrichment.IntervalsEnrichmentStore
import com.runningai.enrichment.IntervalsFitnessDayData
import com.runningai.enrichment.IntervalsPayloadType
import com.runningai.enrichment.MatchEvidence
import com.runningai.enrichment.MatchMethod
import com.runningai.enrichment.MatchResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

/** An enrichment of the same target is already running in this JVM. */
class IntervalsEnrichmentAlreadyRunningException(target: String) :
    RuntimeException("An Intervals enrichment of $target is already running")

enum class MatchStatus { MATCHED, UNMATCHED, AMBIGUOUS }

data class ActivityEnrichmentResult(
    val activityId: Long,
    val matchStatus: MatchStatus,
    val matchMethod: MatchMethod?,
    val intervalsActivityId: String?,
    /** Composite candidates seen when the result is AMBIGUOUS; null otherwise. */
    val candidateCount: Int?,
    val evidence: MatchEvidence?,
    val metrics: ActivityIntervalsMetricsData?,
    /** Candidate items in the window that did not have the live-verified shape (skipped, raw not kept). */
    val unmappedCandidates: Int,
    /** True when no Intervals call was made (reprocess from stored raw). */
    val reprocessed: Boolean,
)

data class FitnessEnrichmentResult(
    val oldest: LocalDate,
    val newest: LocalDate,
    val daysFetched: Int,
    val daysStored: Int,
    val failedDays: Int,
    val reprocessed: Boolean,
)

/** Stored enrichment of one activity, for the read-only API. */
data class ActivityIntervalsView(
    val activityId: Long,
    val link: ActivitySourceLinkData,
    val metrics: ActivityIntervalsMetricsData?,
)

/**
 * Intervals.icu analysis enrichment (Phase 6H-5). Read-only towards Intervals.icu: the only transport
 * dependency is {@link IntervalsReadClient}, which has no write method. No scheduler, no retry; a 401 /
 * 403 / 429 / connector failure propagates immediately and nothing re-calls.
 *
 * Raw-first: the matched activity item / each wellness entry is committed to `intervals_raw_payload`
 * before normalisation, so a mapping failure never loses the payload, and `reprocess*` rebuilds the
 * normalised rows from storage with ZERO Intervals API calls.
 *
 * Boundaries this service never crosses: it does not create Activity rows (an unmatched Intervals
 * activity stays unmatched — UNMATCHED is a normal result), never touches `activity_analysis` (different
 * provenance), never writes Garmin recovery data, and never judges RunningAI vs Intervals numbers.
 */
@Service
class IntervalsEnrichmentService(
    private val readClient: IntervalsReadClient,
    private val activities: ActivityRepository,
    private val athletes: AthleteRepository,
    private val athleteService: AthleteService,
    private val store: IntervalsEnrichmentStore,
    private val activityMapper: IntervalsActivityMapper,
    private val wellnessMapper: IntervalsWellnessMapper,
    private val matcher: IntervalsActivityMatcher,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(IntervalsEnrichmentService::class.java)
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Enriches one activity from Intervals.icu: one `listActivities` GET over a +/-1 day window around
     * the activity's athlete-local date, match, then raw -> link -> metrics. Idempotent: re-running
     * replaces the same rows.
     */
    fun enrichActivity(activityId: Long): ActivityEnrichmentResult = guarded("activity:$activityId") {
        val activity = requireActivity(activityId)
        val zone = athleteZone(activity.athleteId)
        val localDate = activity.startedAt.atZone(zone).toLocalDate()
        val response = readClient.listActivities(localDate.minusDays(1), localDate.plusDays(1))
        val body = response.body()
        if (!body.isArray) {
            throw IntervalsException(IntervalsException.Reason.INVALID_RESPONSE, null,
                "Intervals.icu activities response is not a JSON array")
        }
        var unmapped = 0
        val candidates = mutableListOf<Pair<JsonNode, IntervalsActivitySnapshot>>()
        body.forEach { item ->
            try {
                candidates += item to activityMapper.map(item)
            } catch (e: IntervalsMappingException) {
                unmapped++
                log.warn("Intervals candidate skipped: activityId={} code={}", activityId, e.code)
            }
        }
        when (val match = matcher.match(activity, candidates.map { it.second })) {
            is MatchResult.Unmatched -> {
                log.info("Intervals enrichment unmatched: activityId={} candidates={}", activityId, candidates.size)
                ActivityEnrichmentResult(activityId, MatchStatus.UNMATCHED, null, null, null, null, null, unmapped, false)
            }
            is MatchResult.Ambiguous -> {
                log.warn("Intervals enrichment ambiguous: activityId={} candidates={}", activityId, match.candidateCount)
                ActivityEnrichmentResult(activityId, MatchStatus.AMBIGUOUS, null, null, match.candidateCount, null, null, unmapped, false)
            }
            is MatchResult.Matched -> {
                val item = candidates.first { it.second.intervalsActivityId == match.snapshot.intervalsActivityId }.first
                val metrics = storeMatched(activity, match.snapshot, item, match.method, match.evidence, zone, now())
                ActivityEnrichmentResult(activityId, MatchStatus.MATCHED, match.method,
                    match.snapshot.intervalsActivityId, null, match.evidence, metrics, unmapped, false)
            }
        }
    }

    /** Re-normalises the activity's stored Intervals raw payload. Never contacts Intervals.icu. */
    fun reprocessActivity(activityId: Long): ActivityEnrichmentResult = guarded("activity:$activityId") {
        val activity = requireActivity(activityId)
        val link = store.findLink(activityId, ExternalSource.INTERVALS_ICU)
            ?: throw ResourceNotFoundException("INTERVALS_LINK_NOT_FOUND",
                "Activity $activityId has no Intervals.icu source link; enrich it first")
        val raw = store.findRaw(activity.athleteId, IntervalsPayloadType.ACTIVITY, link.externalActivityId)
            ?: throw ResourceNotFoundException("INTERVALS_RAW_NOT_FOUND",
                "No stored Intervals payload for activity $activityId; enrich it first")
        val snapshot = activityMapper.map(raw.payload)
        // Source-link validation: the stored payload must still describe the linked Intervals activity.
        check(snapshot.intervalsActivityId == link.externalActivityId) {
            "Stored Intervals payload describes ${snapshot.intervalsActivityId}, link says ${link.externalActivityId}"
        }
        // fetchedAt stays the raw payload's real fetch time: a reprocess is not a fetch.
        val metrics = store.upsertMetrics(activityId, snapshot, raw.fetchedAt)
        ActivityEnrichmentResult(activityId, MatchStatus.MATCHED, link.matchMethod,
            link.externalActivityId, null, link.evidence, metrics, 0, true)
    }

    /** Stored link + metrics of one activity, no network. */
    fun view(activityId: Long): ActivityIntervalsView {
        requireActivity(activityId)
        val link = store.findLink(activityId, ExternalSource.INTERVALS_ICU)
            ?: throw ResourceNotFoundException("INTERVALS_ENRICHMENT_NOT_FOUND",
                "Activity $activityId has no Intervals.icu enrichment")
        return ActivityIntervalsView(activityId, link, store.findMetrics(activityId))
    }

    /**
     * Fetches the wellness range (one GET) and upserts each day's fitness-model snapshot. The range is
     * capped at [MAX_FITNESS_RANGE_DAYS] days: this is a manual enrichment window, not a backfill path.
     */
    fun enrichFitness(oldest: LocalDate, newest: LocalDate): FitnessEnrichmentResult = guarded("fitness") {
        requireFitnessRange(oldest, newest)
        val athlete = athleteService.defaultAthlete
        val response = readClient.getWellness(oldest, newest)
        val body = response.body()
        if (!body.isArray) {
            throw IntervalsException(IntervalsException.Reason.INVALID_RESPONSE, null,
                "Intervals.icu wellness response is not a JSON array")
        }
        var stored = 0
        var failed = 0
        body.forEach { entry ->
            val id = entry.get("id")?.takeIf { it.isTextual }?.asText()
            if (id == null) {
                failed++
                log.warn("Intervals wellness entry skipped: no id (raw not storable without one)")
                return@forEach
            }
            val effectiveDate = runCatching { LocalDate.parse(id) }.getOrNull()
            store.saveOrReplaceRaw(athlete.id, IntervalsPayloadType.WELLNESS_DAY, id, effectiveDate, entry, now())
            try {
                store.upsertFitnessDay(athlete.id, wellnessMapper.map(entry), now())
                stored++
            } catch (e: IntervalsMappingException) {
                failed++
                log.warn("Intervals wellness mapping failed: date={} code={} (raw payload preserved)", id, e.code)
            }
        }
        FitnessEnrichmentResult(oldest, newest, body.size(), stored, failed, false)
    }

    /** Re-normalises the stored wellness raw payloads of the range. Never contacts Intervals.icu. */
    fun reprocessFitness(oldest: LocalDate, newest: LocalDate): FitnessEnrichmentResult = guarded("fitness") {
        requireFitnessRange(oldest, newest)
        val athlete = athleteService.defaultAthlete
        var found = 0
        var stored = 0
        var failed = 0
        var date = oldest
        while (!date.isAfter(newest)) {
            val raw = store.findRaw(athlete.id, IntervalsPayloadType.WELLNESS_DAY, date.toString())
            if (raw != null) {
                found++
                try {
                    store.upsertFitnessDay(athlete.id, wellnessMapper.map(raw.payload), raw.fetchedAt)
                    stored++
                } catch (e: IntervalsMappingException) {
                    failed++
                    log.warn("Intervals wellness reprocess failed: date={} code={}", date, e.code)
                }
            }
            date = date.plusDays(1)
        }
        FitnessEnrichmentResult(oldest, newest, found, stored, failed, true)
    }

    /** Stored fitness days of the range, no network. */
    fun fitnessDays(oldest: LocalDate, newest: LocalDate): List<IntervalsFitnessDayData> {
        require(!newest.isBefore(oldest)) { "newest must not be before oldest" }
        return store.fitnessDays(athleteService.defaultAthlete.id, oldest, newest)
    }

    private fun storeMatched(
        activity: Activity,
        snapshot: IntervalsActivitySnapshot,
        item: JsonNode,
        method: MatchMethod,
        evidence: MatchEvidence,
        zone: ZoneId,
        fetchedAt: Instant,
    ): ActivityIntervalsMetricsData {
        // Raw first, own committed transaction: a later link/metrics failure never loses the payload.
        store.saveOrReplaceRaw(
            activity.athleteId, IntervalsPayloadType.ACTIVITY, snapshot.intervalsActivityId,
            snapshot.startDate?.atZone(zone)?.toLocalDate(), item, fetchedAt,
        )
        store.upsertLink(activity.id, ExternalSource.INTERVALS_ICU, snapshot.intervalsActivityId, method, evidence, fetchedAt)
        val metrics = store.upsertMetrics(activity.id, snapshot, fetchedAt)
        log.info("Intervals enrichment stored: activityId={} intervalsActivityId={} method={}",
            activity.id, snapshot.intervalsActivityId, method)
        return metrics
    }

    private fun requireActivity(activityId: Long): Activity =
        activities.findById(activityId).orElseThrow {
            ResourceNotFoundException("ACTIVITY_NOT_FOUND", "Activity $activityId does not exist")
        }

    private fun athleteZone(athleteId: Long): ZoneId {
        val athlete = athletes.findById(athleteId).orElseThrow {
            ResourceNotFoundException("ATHLETE_NOT_FOUND", "Athlete $athleteId does not exist")
        }
        return ZoneId.of(athlete.timezone)
    }

    private fun requireFitnessRange(oldest: LocalDate, newest: LocalDate) {
        require(!newest.isBefore(oldest)) { "newest must not be before oldest" }
        val days = ChronoUnit.DAYS.between(oldest, newest) + 1
        require(days <= MAX_FITNESS_RANGE_DAYS) {
            "Fitness enrichment range is $days days; at most $MAX_FITNESS_RANGE_DAYS allowed (backfill is a separate, not-yet-run phase)"
        }
    }

    private fun <T> guarded(target: String, body: () -> T): T {
        if (!inFlight.add(target)) throw IntervalsEnrichmentAlreadyRunningException(target)
        try {
            return body()
        } finally {
            inFlight.remove(target)
        }
    }

    private fun now(): Instant = Instant.now(clock)

    companion object {
        /** Manual window guard; the 90-day historical backfill is deliberately NOT this endpoint. */
        const val MAX_FITNESS_RANGE_DAYS = 31L
    }
}
