package com.runningai.enrichment

import com.fasterxml.jackson.databind.JsonNode
import com.runningai.activity.ExternalSource
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate

/** The Intervals activity to be linked is already linked to a different RunningAI activity. */
class SourceLinkConflictException(message: String) : RuntimeException(message)

/** A stored raw payload plus when it was actually fetched (reprocess never pretends to have re-fetched). */
data class RawPayloadRecord(val payload: JsonNode, val fetchedAt: Instant, val effectiveDate: LocalDate?)

/**
 * Persistence for Intervals.icu enrichment (Phase 6H-5). Raw payloads are stored in their own committed
 * transaction before any normalisation (same raw-first principle as `ActivityRawPayloadStore`), so a
 * mapping failure can never roll a payload back. Every upsert replaces in place, which is what makes a
 * second enrichment of the same content idempotent: no duplicate rows, the V17/V18 unique keys are the
 * last line of defence.
 */
@Service
class IntervalsEnrichmentStore(
    private val rawPayloads: IntervalsRawPayloadRepository,
    private val links: ActivitySourceLinkRepository,
    private val metrics: ActivityIntervalsMetricsRepository,
    private val fitnessDays: IntervalsFitnessDailyRepository,
) {

    /** Stores [payload], or replaces the stored payload of the same (athlete, type, external id). */
    @Transactional
    fun saveOrReplaceRaw(
        athleteId: Long,
        type: IntervalsPayloadType,
        externalId: String,
        effectiveDate: LocalDate?,
        payload: JsonNode,
        fetchedAt: Instant,
    ): Long {
        val existing = rawPayloads.findByAthleteIdAndPayloadTypeAndExternalId(athleteId, type, externalId)
        val row = if (existing != null) {
            existing.payload = payload
            existing.effectiveDate = effectiveDate
            existing.fetchedAt = fetchedAt
            existing
        } else {
            rawPayloads.save(IntervalsRawPayloadEntity(athleteId, type, externalId, effectiveDate, payload, fetchedAt))
        }
        rawPayloads.flush()
        return requireNotNull(row.id)
    }

    @Transactional(readOnly = true)
    fun findRaw(athleteId: Long, type: IntervalsPayloadType, externalId: String): RawPayloadRecord? =
        rawPayloads.findByAthleteIdAndPayloadTypeAndExternalId(athleteId, type, externalId)
            ?.let { RawPayloadRecord(it.payload, it.fetchedAt, it.effectiveDate) }

    /**
     * Links [activityId] to one external activity, updating the existing link of the same
     * (activity, source) in place.
     *
     * @throws SourceLinkConflictException when that external activity is already linked to a DIFFERENT
     *         RunningAI activity — two RunningAI activities must never claim the same external one, and
     *         resolving that is a manual decision, not an automatic re-link
     */
    @Transactional
    fun upsertLink(
        activityId: Long,
        source: ExternalSource,
        externalActivityId: String,
        method: MatchMethod,
        evidence: MatchEvidence,
        matchedAt: Instant,
    ): ActivitySourceLinkData {
        val claimedBy = links.findByExternalSourceAndExternalActivityId(source, externalActivityId)
        if (claimedBy != null && claimedBy.activityId != activityId) {
            throw SourceLinkConflictException(
                "$source activity $externalActivityId is already linked to activity ${claimedBy.activityId}")
        }
        val existing = links.findByActivityIdAndExternalSource(activityId, source)
        val row = if (existing != null) {
            existing.externalActivityId = externalActivityId
            existing.matchMethod = method
            existing.startTimeDeltaSeconds = evidence.startTimeDeltaSeconds
            existing.durationDeltaSeconds = evidence.durationDeltaSeconds
            existing.distanceDeltaMeters = evidence.distanceDeltaMeters
            existing.matchedAt = matchedAt
            existing
        } else {
            links.save(ActivitySourceLinkEntity(
                activityId, source, externalActivityId, method,
                evidence.startTimeDeltaSeconds, evidence.durationDeltaSeconds, evidence.distanceDeltaMeters,
                matchedAt,
            ))
        }
        links.flush()
        return row.toData()
    }

    @Transactional(readOnly = true)
    fun findLink(activityId: Long, source: ExternalSource): ActivitySourceLinkData? =
        links.findByActivityIdAndExternalSource(activityId, source)?.toData()

    /** Replaces the activity's Intervals metrics (1:1). */
    @Transactional
    fun upsertMetrics(activityId: Long, snapshot: IntervalsActivitySnapshot, fetchedAt: Instant): ActivityIntervalsMetricsData {
        val row = metrics.findByActivityId(activityId) ?: ActivityIntervalsMetricsEntity(activityId, snapshot.intervalsActivityId)
        row.apply(snapshot, fetchedAt)
        return metrics.save(row).toData()
    }

    @Transactional(readOnly = true)
    fun findMetrics(activityId: Long): ActivityIntervalsMetricsData? = metrics.findByActivityId(activityId)?.toData()

    /** Replaces one athlete-local day's fitness snapshot. */
    @Transactional
    fun upsertFitnessDay(athleteId: Long, day: IntervalsWellnessDay, fetchedAt: Instant): IntervalsFitnessDayData {
        val row = fitnessDays.findByAthleteIdAndFitnessDate(athleteId, day.date)
            ?: IntervalsFitnessDailyEntity(athleteId, day.date)
        row.apply(day, fetchedAt)
        return fitnessDays.save(row).toData()
    }

    @Transactional(readOnly = true)
    fun fitnessDays(athleteId: Long, oldest: LocalDate, newest: LocalDate): List<IntervalsFitnessDayData> =
        fitnessDays.findByAthleteIdAndFitnessDateBetweenOrderByFitnessDate(athleteId, oldest, newest).map { it.toData() }
}
