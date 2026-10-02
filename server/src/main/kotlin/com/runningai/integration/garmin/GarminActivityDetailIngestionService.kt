package com.runningai.integration.garmin

import com.fasterxml.jackson.databind.JsonNode
import com.runningai.activity.ActivityRawService
import com.runningai.activity.ActivityRepository
import com.runningai.activity.ExternalSource
import com.runningai.activity.detail.ActivityDetailStore
import com.runningai.activity.detail.ActivityRawPayloadStore
import com.runningai.activity.detail.DetailCollectionOutcome
import com.runningai.activity.detail.DetailPartStatus
import com.runningai.activity.detail.DetailPayloadType
import com.runningai.activity.detail.ZoneType
import com.runningai.common.exception.ResourceNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** A detail collection (or reprocess) of the same Garmin activity is already running in this JVM. */
class GarminDetailCollectionAlreadyRunningException(garminActivityId: String) :
    RuntimeException("A detail collection of Garmin activity $garminActivityId is already running")

/** One part's outcome in a collection run; [status] null = not attempted (the run stopped before it). */
data class DetailPartResult(
    val payloadType: DetailPayloadType,
    val status: DetailPartStatus?,
    val errorCode: String?,
    val itemCount: Int?,
)

data class DetailCollectionResult(
    val garminActivityId: String,
    val activityId: Long,
    val outcome: DetailCollectionOutcome,
    val parts: List<DetailPartResult>,
)

/**
 * Raw-first collection of one Garmin activity's detail (Phase 6H-1A). For every part:
 *
 *   connector fetch -> raw payload stored (own transaction, committed)
 *                   -> pure mapper (may fail: raw stays, status MAPPING_FAILED, old rows untouched)
 *                   -> normalised rows replaced (own transaction) -> part status recorded
 *
 * The activity-level detail is normalised from the activity-list item already in `activity_raw`
 * (no Garmin call). The `get_activity` payload is stored raw only: its field names are not statically
 * confirmed, so it is not normalised until Main-PC Phase 6H-1B confirms them.
 *
 * Failure policy: a part's failure never hides behind the stored summary activity; it is recorded and
 * the run reports PARTIAL / FAILED. A per-part failure (404, 5xx, unexpected body, mapping error) lets
 * the other parts continue. An account-level failure (auth, forbidden, rate limit, connector down)
 * records that part and stops the run immediately, then propagates, so nothing keeps calling Garmin.
 * Never retried. Not transactional itself (no transaction across a connector call), and the base
 * activity must already exist (created by the activity-list ingestion), so ids are never invented.
 *
 * Re-running is idempotent: raw rows are replaced per (activity, type), normalised rows per part.
 * In-JVM single-flight per Garmin activity id.
 */
@Service
class GarminActivityDetailIngestionService(
    private val source: GarminActivityDetailSource,
    private val activities: ActivityRepository,
    private val activityRaws: ActivityRawService,
    private val rawPayloads: ActivityRawPayloadStore,
    private val store: ActivityDetailStore,
    private val detailMapper: GarminActivityDetailMapper,
    private val lapMapper: GarminLapMapper,
    private val zoneMapper: GarminZoneMapper,
    private val sampleMapper: GarminSampleMapper,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(GarminActivityDetailIngestionService::class.java)
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Fetches every remote part from the connector and normalises it. */
    fun collect(garminActivityId: String): DetailCollectionResult = guarded(garminActivityId) { activityId, athleteId ->
        val results = mutableListOf(normalizeListItem(garminActivityId, activityId))
        val remaining = GarminActivityPart.entries.toMutableList()
        while (remaining.isNotEmpty()) {
            val part = remaining.removeAt(0)
            val payload = try {
                source.fetch(part, garminActivityId)
            } catch (e: GarminConnectorException) {
                results += record(activityId, part.payloadType, DetailPartStatus.FETCH_FAILED, e.reason.name, null)
                if (e.reason in STOP_REASONS) {
                    log.warn("Garmin detail collection stopped: externalId={} part={} reason={}",
                        garminActivityId, part.path, e.reason)
                    throw e
                }
                continue
            }
            rawPayloads.saveOrReplace(athleteId, ExternalSource.GARMIN, garminActivityId, part.payloadType, payload, now())
            results += normalize(activityId, part.payloadType, payload)
        }
        finish(garminActivityId, activityId, results)
    }

    /** Re-normalises every stored raw payload of the activity. Never contacts Garmin. */
    fun reprocess(garminActivityId: String): DetailCollectionResult = guarded(garminActivityId) { activityId, _ ->
        val results = mutableListOf(normalizeListItem(garminActivityId, activityId))
        val stored = rawPayloads.storedTypes(ExternalSource.GARMIN, garminActivityId)
        GarminActivityPart.entries.map { it.payloadType }.filter { it in stored }.forEach { type ->
            val payload = requireNotNull(rawPayloads.find(ExternalSource.GARMIN, garminActivityId, type))
            results += normalize(activityId, type, payload)
        }
        finish(garminActivityId, activityId, results)
    }

    private fun guarded(garminActivityId: String, body: (Long, Long) -> DetailCollectionResult): DetailCollectionResult {
        requireValidGarminActivityId(garminActivityId)
        val activity = activities.findByExternalSourceAndExternalId(ExternalSource.GARMIN, garminActivityId)
            .orElseThrow {
                ResourceNotFoundException("ACTIVITY_NOT_FOUND",
                    "No Garmin activity $garminActivityId has been ingested; sync the activity list first")
            }
        if (!inFlight.add(garminActivityId)) throw GarminDetailCollectionAlreadyRunningException(garminActivityId)
        try {
            log.info("Garmin detail collection started: externalId={} activityId={}", garminActivityId, activity.id)
            return body(activity.id, activity.athleteId)
        } finally {
            inFlight.remove(garminActivityId)
        }
    }

    private fun normalizeListItem(garminActivityId: String, activityId: Long): DetailPartResult {
        val raw = activityRaws.find(ExternalSource.GARMIN, garminActivityId).orElse(null)
            ?: return record(activityId, DetailPayloadType.ACTIVITY_LIST, DetailPartStatus.MAPPING_FAILED, "RAW_MISSING", null)
        return try {
            store.replaceDetail(activityId, detailMapper.map(raw.payload))
            record(activityId, DetailPayloadType.ACTIVITY_LIST, DetailPartStatus.NORMALIZED, null, 1)
        } catch (e: GarminDetailMappingException) {
            mappingFailed(activityId, DetailPayloadType.ACTIVITY_LIST, e)
        }
    }

    private fun normalize(activityId: Long, type: DetailPayloadType, payload: JsonNode): DetailPartResult = try {
        when (type) {
            DetailPayloadType.ACTIVITY_DETAIL -> record(activityId, type, DetailPartStatus.RAW_STORED, null, null)
            DetailPayloadType.SPLITS -> counted(activityId, type, store.replaceLaps(activityId, lapMapper.map(payload)))
            DetailPayloadType.HR_ZONES ->
                counted(activityId, type, store.replaceZones(activityId, ZoneType.HEART_RATE, zoneMapper.map(payload, ZoneType.HEART_RATE)))
            DetailPayloadType.POWER_ZONES ->
                counted(activityId, type, store.replaceZones(activityId, ZoneType.POWER, zoneMapper.map(payload, ZoneType.POWER)))
            DetailPayloadType.ACTIVITY_DETAILS_STREAM ->
                counted(activityId, type, store.replaceSamples(activityId, sampleMapper.map(payload)))
            DetailPayloadType.ACTIVITY_LIST -> error("the activity-list item is normalised separately")
        }
    } catch (e: GarminDetailMappingException) {
        mappingFailed(activityId, type, e)
    }

    private fun counted(activityId: Long, type: DetailPayloadType, count: Int) =
        record(activityId, type, if (count == 0) DetailPartStatus.EMPTY else DetailPartStatus.NORMALIZED, null, count)

    private fun mappingFailed(activityId: Long, type: DetailPayloadType, e: GarminDetailMappingException): DetailPartResult {
        log.warn("Garmin detail mapping failed: activityId={} part={} code={} (raw payload preserved)", activityId, type, e.code)
        return record(activityId, type, DetailPartStatus.MAPPING_FAILED, e.code, null)
    }

    private fun record(activityId: Long, type: DetailPayloadType, status: DetailPartStatus, errorCode: String?, count: Int?): DetailPartResult {
        store.recordPart(activityId, type, status, errorCode, count, now())
        return DetailPartResult(type, status, errorCode, count)
    }

    private fun finish(garminActivityId: String, activityId: Long, attempted: List<DetailPartResult>): DetailCollectionResult {
        val attemptedTypes = attempted.map { it.payloadType }.toSet()
        val skipped = (listOf(DetailPayloadType.ACTIVITY_LIST) + GarminActivityPart.entries.map { it.payloadType })
            .filter { it !in attemptedTypes }
            .map { DetailPartResult(it, null, null, null) }
        val failures = attempted.count { it.status?.failed == true }
        val outcome = when {
            failures == attempted.size -> DetailCollectionOutcome.FAILED
            failures == 0 && skipped.isEmpty() -> DetailCollectionOutcome.COMPLETE
            else -> DetailCollectionOutcome.PARTIAL
        }
        log.info("Garmin detail collection finished: externalId={} activityId={} outcome={} failedParts={}",
            garminActivityId, activityId, outcome, failures)
        return DetailCollectionResult(garminActivityId, activityId, outcome, attempted + skipped)
    }

    private fun now(): Instant = Instant.now(clock)

    private companion object {
        /** Account-level failures: stop the run instead of calling Garmin for the next part. */
        val STOP_REASONS = setOf(
            GarminConnectorException.Reason.AUTH_REQUIRED,
            GarminConnectorException.Reason.FORBIDDEN,
            GarminConnectorException.Reason.RATE_LIMITED,
            GarminConnectorException.Reason.UNAVAILABLE,
        )
    }
}
