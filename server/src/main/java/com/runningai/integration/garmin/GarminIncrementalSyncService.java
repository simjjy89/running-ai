package com.runningai.integration.garmin;

import com.fasterxml.jackson.databind.JsonNode;
import com.runningai.athlete.AthleteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Incremental Garmin sync: high-water mark + overlap window + idempotent
 * ingestion, replacing a plain "fetch the last N" cursor.
 * <pre>
 *   no GarminSyncState  -> bootstrap: one page (page-size items), no cutoff
 *   GarminSyncState     -> cutoff = highWaterStartedAt - overlap
 *                          page newest -> oldest until a page's oldest parseable
 *                          startTime reaches the cutoff, a short/empty page ends
 *                          Garmin's own history, or max-pages is exhausted
 * </pre>
 * The checkpoint only advances when the whole run succeeded: no connector-level
 * failure (propagates and aborts before the checkpoint is touched), no malformed
 * item ({@code failed == 0}), and — for an incremental run — the cutoff was
 * actually reached within {@code max-pages} (otherwise {@link GarminIncrementalSyncException}).
 * Activities already ingested by a run that does not advance the checkpoint are
 * kept; the next run re-covers the same ground via the overlap and idempotency.
 */
@Service
public class GarminIncrementalSyncService {

    private static final Logger log = LoggerFactory.getLogger(GarminIncrementalSyncService.class);

    private final GarminActivitySource activitySource;
    private final GarminActivityIngestionService ingestionService;
    private final GarminActivityMapper mapper;
    private final GarminSyncStateService syncStateService;
    private final AthleteService athleteService;
    private final GarminIncrementalSyncProperties properties;

    public GarminIncrementalSyncService(GarminActivitySource activitySource,
                                        GarminActivityIngestionService ingestionService,
                                        GarminActivityMapper mapper,
                                        GarminSyncStateService syncStateService,
                                        AthleteService athleteService,
                                        GarminIncrementalSyncProperties properties) {
        this.activitySource = activitySource;
        this.ingestionService = ingestionService;
        this.mapper = mapper;
        this.syncStateService = syncStateService;
        this.athleteService = athleteService;
        this.properties = properties;
    }

    /**
     * @throws GarminConnectorException        when the connector or Garmin fails; the checkpoint is not advanced
     * @throws GarminIncrementalSyncException  when max-pages was exhausted before the overlap cutoff was reached
     */
    public GarminIncrementalSyncResult syncIncremental() {
        Long athleteId = athleteService.getDefaultAthlete().getId();
        Optional<GarminSyncState> state = syncStateService.find(athleteId);
        boolean bootstrap = state.isEmpty();
        Instant cutoff = state.map(s -> s.getHighWaterStartedAt().minus(properties.overlap())).orElse(null);
        Instant fetchedAt = Instant.now();

        int fetched = 0;
        int created = 0;
        int updated = 0;
        int skipped = 0;
        int failed = 0;
        int pagesFetched = 0;
        Instant maxStartTimeSeen = null;
        boolean cutoffReached = bootstrap;

        int maxPagesThisRun = bootstrap ? 1 : properties.maxPages();
        for (int page = 0; page < maxPagesThisRun; page++) {
            int start = page * properties.pageSize();
            List<JsonNode> items = activitySource.fetchActivities(start, properties.pageSize());
            pagesFetched++;
            fetched += items.size();

            if (items.isEmpty()) {
                cutoffReached = true;
                break;
            }

            Instant pageMinStartTime = null;
            for (JsonNode item : items) {
                Instant parsedStartTime = tryParseStartTime(item);
                if (parsedStartTime != null) {
                    if (maxStartTimeSeen == null || parsedStartTime.isAfter(maxStartTimeSeen)) {
                        maxStartTimeSeen = parsedStartTime;
                    }
                    if (pageMinStartTime == null || parsedStartTime.isBefore(pageMinStartTime)) {
                        pageMinStartTime = parsedStartTime;
                    }
                }

                try {
                    GarminIngestionResult result = ingestionService.ingest(item, fetchedAt);
                    if (result.created()) {
                        created++;
                    } else {
                        updated++;
                    }
                } catch (GarminActivityMappingException e) {
                    if (e.getReason() == GarminActivityMappingException.Reason.UNSUPPORTED_ACTIVITY_TYPE) {
                        skipped++;
                    } else {
                        failed++;
                        log.warn("Garmin incremental sync failed to ingest activity: externalId={} code={}",
                                e.getGarminActivityId(), e.getCode());
                    }
                } catch (RuntimeException e) {
                    failed++;
                    log.warn("Garmin incremental sync failed to ingest activity: {}", e.getClass().getSimpleName(), e);
                }
            }

            if (!bootstrap && pageMinStartTime != null && !pageMinStartTime.isAfter(cutoff)) {
                cutoffReached = true;
                break;
            }
            if (items.size() < properties.pageSize()) {
                cutoffReached = true;
                break;
            }
        }

        if (!bootstrap && !cutoffReached) {
            throw new GarminIncrementalSyncException(
                    "Incremental sync window incomplete: reached max-pages (" + properties.maxPages()
                            + ") before the overlap cutoff; checkpoint not advanced");
        }

        boolean checkpointAdvanced = false;
        Instant previousHighWater = state.map(GarminSyncState::getHighWaterStartedAt).orElse(null);
        Instant highWaterStartedAt = previousHighWater;
        if (failed == 0) {
            Instant candidate = maxStartTimeSeen != null ? maxStartTimeSeen : highWaterStartedAt;
            if (candidate != null) {
                GarminSyncState advanced = syncStateService.advance(athleteId, candidate, Instant.now());
                highWaterStartedAt = advanced.getHighWaterStartedAt();
                // "advanced" = the high-water mark moved forward (or was created). A run that only
                // refreshes lastSuccessfulSyncAt leaves the high-water mark alone and reports false.
                checkpointAdvanced = previousHighWater == null || highWaterStartedAt.isAfter(previousHighWater);
            }
        }

        GarminIncrementalSyncResult result = new GarminIncrementalSyncResult(
                fetched, created, updated, skipped, failed, pagesFetched, checkpointAdvanced, highWaterStartedAt);
        log.info("Garmin incremental sync completed: fetched={} created={} updated={} skipped={} failed={} "
                        + "pagesFetched={} checkpointAdvanced={}",
                result.fetched(), result.created(), result.updated(), result.skipped(), result.failed(),
                result.pagesFetched(), result.checkpointAdvanced());
        return result;
    }

    /** Peeks at the start time only; a payload that cannot be parsed at all does not count towards the cutoff. */
    private Instant tryParseStartTime(JsonNode item) {
        try {
            return mapper.parse(item).startTime();
        } catch (GarminActivityMappingException e) {
            return null;
        }
    }
}
