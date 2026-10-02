package com.runningai.activity.detail

import com.fasterxml.jackson.databind.JsonNode
import com.runningai.activity.ExternalSource
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Raw-first storage of detail payloads (`activity_raw_payload`). Each call is its own committed
 * transaction, so a later normalisation failure can never roll a stored payload back.
 */
@Service
class ActivityRawPayloadStore(private val repository: ActivityRawPayloadRepository) {

    /** Stores [payload], or replaces the stored snapshot of the same activity and type. Returns the row id. */
    @Transactional
    fun saveOrReplace(
        athleteId: Long,
        source: ExternalSource,
        externalActivityId: String,
        type: DetailPayloadType,
        payload: JsonNode,
        fetchedAt: Instant,
    ): Long {
        require(type != DetailPayloadType.ACTIVITY_LIST) { "The activity-list item is stored in activity_raw" }
        val existing = repository.findByExternalSourceAndExternalActivityIdAndPayloadType(source, externalActivityId, type)
        val row = if (existing != null) {
            existing.payload = payload
            existing.fetchedAt = fetchedAt
            existing
        } else {
            repository.save(ActivityRawPayloadEntity(athleteId, source, externalActivityId, type, payload, fetchedAt))
        }
        repository.flush()
        return requireNotNull(row.id)
    }

    @Transactional(readOnly = true)
    fun find(source: ExternalSource, externalActivityId: String, type: DetailPayloadType): JsonNode? =
        repository.findByExternalSourceAndExternalActivityIdAndPayloadType(source, externalActivityId, type)?.payload

    @Transactional(readOnly = true)
    fun storedTypes(source: ExternalSource, externalActivityId: String): Set<DetailPayloadType> =
        repository.findByExternalSourceAndExternalActivityId(source, externalActivityId).map { it.payloadType }.toSet()
}

/**
 * Normalised detail rows. Every write **replaces** what an activity had for that part, inside one
 * transaction (delete + insert), so re-collecting or reprocessing the same activity is idempotent and
 * never leaves a mix of old and new rows. The unique keys of V12 back this up.
 */
@Service
class ActivityDetailStore(
    private val details: ActivityDetailRepository,
    private val laps: ActivityLapRepository,
    private val zones: ActivityZoneRepository,
    private val samples: ActivitySampleRepository,
    private val collections: ActivityDetailCollectionRepository,
) {

    @Transactional
    fun replaceDetail(activityId: Long, data: ActivityDetailData) {
        val row = details.findByActivityId(activityId) ?: ActivityDetailEntity(activityId)
        row.apply(data)
        details.save(row)
    }

    @Transactional
    fun replaceLaps(activityId: Long, data: List<LapData>): Int {
        laps.deleteAllOfActivity(activityId)
        laps.saveAll(data.map { ActivityLapEntity.of(activityId, it) })
        return data.size
    }

    @Transactional
    fun replaceZones(activityId: Long, type: ZoneType, data: List<ZoneData>): Int {
        require(data.all { it.zoneType == type }) { "every zone must be of type $type" }
        zones.deleteAllOfActivity(activityId, type)
        zones.saveAll(data.map { ActivityZoneEntity.of(activityId, it) })
        return data.size
    }

    @Transactional
    fun replaceSamples(activityId: Long, data: List<SampleData>): Int {
        samples.deleteAllOfActivity(activityId)
        samples.saveAll(data.map { ActivitySampleEntity.of(activityId, it) })
        return data.size
    }

    /** Records (or overwrites) the outcome of one part for one activity. */
    @Transactional
    fun recordPart(activityId: Long, record: DetailPartRecord) {
        val row = collections.findByActivityIdAndPayloadType(activityId, record.payloadType)
        if (row == null) {
            collections.save(ActivityDetailCollectionEntity(activityId, record.payloadType, record.status, null, null, record.attemptedAt)
                .apply { apply(record) })
        } else {
            row.apply(record)
        }
    }

    /** What the last collection of [type] recorded, or null if that part was never attempted. */
    @Transactional(readOnly = true)
    fun part(activityId: Long, type: DetailPayloadType): DetailPartRecord? =
        collections.findByActivityIdAndPayloadType(activityId, type)?.toRecord()

    @Transactional(readOnly = true)
    fun detail(activityId: Long): ActivityDetailData? = details.findByActivityId(activityId)?.toData()

    @Transactional(readOnly = true)
    fun laps(activityId: Long): List<LapData> = laps.findByActivityIdOrderByLapIndex(activityId).map { it.toData() }

    @Transactional(readOnly = true)
    fun zones(activityId: Long, type: ZoneType): List<ZoneData> =
        zones.findByActivityIdAndZoneTypeOrderByZoneNumber(activityId, type).map { it.toData() }

    @Transactional(readOnly = true)
    fun samples(activityId: Long): List<SampleData> =
        samples.findByActivityIdOrderBySampleIndex(activityId).map { it.toData() }

    @Transactional(readOnly = true)
    fun collection(activityId: Long): List<DetailPartRecord> =
        collections.findByActivityId(activityId).map { it.toRecord() }.sortedBy { it.payloadType }
}
