package com.runningai.enrichment

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Persistence of the Intervals-only importer's per-activity state (Phase 7E-1, V25).
 *
 * Every write commits in its own transaction ([Propagation.REQUIRES_NEW]), the same discipline the
 * historical backfill uses for its checkpoints and for the same reason: the row has to survive whatever
 * happens to the orchestrator afterwards, or a re-run cannot know what the previous run finished. The
 * orchestrator itself is never transactional - it makes network calls.
 */
@Service
class IntervalsActivityImportStore(private val imports: IntervalsActivityImportRepository) {

    @Transactional(readOnly = true)
    fun find(athleteId: Long, intervalsActivityId: String): IntervalsImportRecord? =
        imports.findByAthleteIdAndIntervalsActivityId(athleteId, intervalsActivityId)?.toData()

    @Transactional(readOnly = true)
    fun all(athleteId: Long): List<IntervalsImportRecord> =
        imports.findByAthleteIdOrderByStartedAtAsc(athleteId).map { it.toData() }

    /**
     * Inserts or replaces the record of one Intervals activity.
     *
     * [attemptCountDelta] is added to whatever the row already counted, so a retried activity keeps its
     * history instead of starting over at 1 - how often an activity has been attempted is part of what
     * makes a repeated failure visible.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun record(athleteId: Long, record: IntervalsImportRecord, attemptCountDelta: Int): IntervalsImportRecord {
        val row = imports.findByAthleteIdAndIntervalsActivityId(athleteId, record.intervalsActivityId)
            ?: IntervalsActivityImportEntity(athleteId, record.intervalsActivityId)
        val previousAttempts = row.attemptCount
        row.activityId = record.activityId
        row.outcome = record.outcome
        row.reasonCode = record.reasonCode
        row.sourceLabel = record.sourceLabel
        row.sourceExternalId = record.sourceExternalId
        row.stravaActivityId = record.stravaActivityId
        row.deviceName = record.deviceName
        row.activityTypeLabel = record.activityTypeLabel
        row.startedAt = record.startedAt
        row.startDateLocal = record.startDateLocal
        row.elapsedTimeSeconds = record.elapsedTimeSeconds
        row.movingTimeSeconds = record.movingTimeSeconds
        row.distanceMeters = record.distanceMeters
        row.sourceLapCount = record.sourceLapCount
        row.streamTypesAvailable = record.streamTypesAvailable
        row.streamTypesImported = record.streamTypesImported
        row.missingStreamTypes = record.missingStreamTypes
        row.sampleCount = record.sampleCount
        row.sampleSpanSeconds = record.sampleSpanSeconds
        row.qualityFlags = record.qualityFlags
        row.sourceAnalyzedAt = record.sourceAnalyzedAt
        row.attemptCount = previousAttempts + attemptCountDelta
        row.lastErrorCode = record.lastErrorCode
        row.importedAt = record.importedAt
        return imports.save(row).toData()
    }
}
