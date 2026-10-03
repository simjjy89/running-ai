package com.runningai.backfill

import com.runningai.integration.garmin.GarminActivityIngestionService
import com.runningai.integration.garmin.GarminActivityMapper
import com.runningai.integration.garmin.GarminActivityMappingException
import com.runningai.integration.garmin.GarminActivitySource
import com.runningai.integration.garmin.GarminConnectorException
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * The run stopped at a safe point. The orchestrator records [reason] as the run's stop reason and
 * marks it PAUSED; a manual resume continues from the stored checkpoints. Never auto-resumed.
 */
class BackfillPauseException(val reason: String, message: String = reason) : RuntimeException(message)

@ConfigurationProperties(prefix = "running-ai.historical-backfill")
data class HistoricalBackfillProperties(
    /** Garmin activity-list page size for discovery (connector limit is 100). */
    val pageSize: Int = 100,
    /** Safety cap on discovery pages; exceeding it pauses the run instead of silently truncating. */
    val maxPages: Int = 20,
    /** Safety cap on in-window activities; exceeding it pauses the run instead of silently truncating. */
    val maxActivities: Int = 500,
    /** Pause between two activities whose detail is actually fetched from Garmin. */
    val activityDelay: Duration = Duration.ofSeconds(2),
)

/**
 * Discovery (phase GARMIN_DISCOVERY): walks the Garmin activity list newest -> oldest from offset 0,
 * ingests every supported in-window activity through the EXISTING summary ingestion (no new mapper),
 * and records one checkpoint row per seen in-window activity. Unsupported types are recorded as
 * SKIPPED_UNSUPPORTED and never invented a mapping.
 *
 * Deliberately not the incremental sync: `garmin_sync_state` is never read or advanced here - this
 * class has no dependency that could reach it. A resumed discovery always restarts at offset 0
 * (offset drift must not lose an activity; everything it re-does is idempotent), and once a full pass
 * completed the target set is frozen (ordinals assigned oldest -> newest).
 */
@Service
class GarminHistoricalActivityDiscoveryService(
    private val source: GarminActivitySource,
    private val mapper: GarminActivityMapper,
    private val ingestion: GarminActivityIngestionService,
    private val store: HistoricalBackfillStore,
    private val properties: HistoricalBackfillProperties,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(GarminHistoricalActivityDiscoveryService::class.java)

    data class DiscoveryResult(val pages: Int, val targetActivities: Int)

    fun discover(run: HistoricalBackfillRunEntity, zone: ZoneId): DiscoveryResult {
        val runId = requireNotNull(run.id)
        var offset = 0
        var pages = 0
        var inWindow = 0
        var reachedOlderBoundary = false
        while (true) {
            if (pages >= properties.maxPages) {
                throw BackfillPauseException("BACKFILL_DISCOVERY_LIMIT_EXCEEDED",
                    "Discovery exceeded max-pages=${properties.maxPages} before reaching the window start")
            }
            val page = try {
                source.fetchActivities(offset, properties.pageSize)
            } catch (e: GarminConnectorException) {
                throw BackfillPauseException("GARMIN_${e.reason.name}", "Garmin discovery stopped: ${e.reason}")
            }
            pages++
            for (item in page) {
                val parsed = try {
                    mapper.parse(item)
                } catch (e: GarminActivityMappingException) {
                    // Without id/start time the item cannot even be placed in the window: fail closed.
                    throw BackfillPauseException(e.code, "Discovery could not place an activity: ${e.code}")
                }
                val localDate = parsed.startTime().atZone(zone).toLocalDate()
                if (localDate.isAfter(run.endDate)) continue
                if (localDate.isBefore(run.startDate)) {
                    reachedOlderBoundary = true
                    continue
                }
                inWindow++
                if (inWindow > properties.maxActivities) {
                    throw BackfillPauseException("BACKFILL_DISCOVERY_LIMIT_EXCEEDED",
                        "Discovery exceeded max-activities=${properties.maxActivities}")
                }
                val (activityId, summary) = try {
                    val result = ingestion.ingest(item, Instant.now(clock))
                    result.activityId to if (result.created()) BackfillSummaryStatus.CREATED else BackfillSummaryStatus.UPDATED
                } catch (e: GarminActivityMappingException) {
                    if (e.reason == GarminActivityMappingException.Reason.UNSUPPORTED_ACTIVITY_TYPE) {
                        null to BackfillSummaryStatus.SKIPPED_UNSUPPORTED
                    } else {
                        throw BackfillPauseException(e.code, "Discovery summary mapping failed: ${e.code}")
                    }
                }
                store.upsertDiscoveredItem(runId, parsed.activityId(), parsed.startTime(), activityId, summary)
            }
            if (reachedOlderBoundary || page.size < properties.pageSize) break
            offset += properties.pageSize
        }
        val targets = store.freezeOrdinals(runId)
        log.info("Historical discovery complete: runId={} pages={} inWindow={} targets={}", runId, pages, inWindow, targets)
        return DiscoveryResult(pages, targets)
    }
}
