package com.runningai.enrichment

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Persistence of the Strava archive importer's per-activity state (Phase 7E-2, V26).
 *
 * Every write commits in its own transaction ([Propagation.REQUIRES_NEW]), the same discipline the
 * historical backfill uses for its checkpoints and for the same reason: the row has to survive whatever
 * happens to the orchestrator afterwards, or a re-run cannot know what the previous run finished. The
 * orchestrator itself is never transactional - it reads files and can run for minutes.
 */
@Service
class StravaActivityImportStore(private val imports: StravaActivityImportRepository) {

    @Transactional(readOnly = true)
    fun find(athleteId: Long, stravaActivityId: String): StravaImportRecord? =
        imports.findByAthleteIdAndStravaActivityId(athleteId, stravaActivityId)?.toData()

    @Transactional(readOnly = true)
    fun all(athleteId: Long): List<StravaImportRecord> =
        imports.findByAthleteIdOrderByStartedAtAsc(athleteId).map { it.toData() }

    /**
     * Inserts or replaces the record of one archive activity.
     *
     * [attemptCountDelta] is added to what the row already counted, so a retried file keeps its history
     * instead of starting over at 1 - how often a file has been attempted is part of what makes a
     * repeated failure visible.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun record(athleteId: Long, record: StravaImportRecord, attemptCountDelta: Int): StravaImportRecord {
        val row = imports.findByAthleteIdAndStravaActivityId(athleteId, record.stravaActivityId)
            ?: StravaActivityImportEntity(athleteId, record.stravaActivityId)
        val previousAttempts = row.attemptCount
        row.activityId = record.activityId
        row.outcome = record.outcome
        row.reasonCode = record.reasonCode
        row.archiveFilename = record.archiveFilename
        row.fileSha256 = record.fileSha256
        row.appleHealthUuid = record.appleHealthUuid
        row.matchedBy = record.matchedBy
        row.deviceManufacturer = record.deviceManufacturer
        row.fitSport = record.fitSport
        row.fitSubSport = record.fitSubSport
        row.activityType = record.activityType
        row.startedAt = record.startedAt
        row.elapsedTimeSeconds = record.elapsedTimeSeconds
        row.timerTimeSeconds = record.timerTimeSeconds
        row.distanceMeters = record.distanceMeters
        row.recordCount = record.recordCount
        row.lapCount = record.lapCount
        row.sampleCount = record.sampleCount
        row.lapsWritten = record.lapsWritten
        row.samplesEnriched = record.samplesEnriched
        row.sensorChannels = record.sensorChannels
        row.qualityFlags = record.qualityFlags
        row.attemptCount = previousAttempts + attemptCountDelta
        row.lastErrorCode = record.lastErrorCode
        row.importedAt = record.importedAt
        return imports.save(row).toData()
    }
}
