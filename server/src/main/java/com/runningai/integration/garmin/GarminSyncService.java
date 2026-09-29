package com.runningai.integration.garmin;

import com.fasterxml.jackson.databind.JsonNode;
import com.runningai.integration.garmin.GarminActivityMappingException.Reason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Pulls the most recent Garmin activities through the connector and feeds each one to
 * the existing raw-first ingestion core.
 * <pre>
 *   GarminActivitySource.fetchRecentActivities(limit)   -- connector failure => abort (exception)
 *     -> for each item: GarminActivityIngestionService.ingest(item, fetchedAt)
 *          created / updated                            -> counted
 *          UNSUPPORTED_GARMIN_ACTIVITY_TYPE              -> skipped (raw kept), continue
 *          other mapping failure (malformed)             -> failed, continue
 * </pre>
 * No scheduler, no cursor: idempotent re-syncs of the same recent window rely on the
 * ingestion service's upsert semantics.
 */
@Service
public class GarminSyncService {

    private static final Logger log = LoggerFactory.getLogger(GarminSyncService.class);

    private final GarminActivitySource activitySource;
    private final GarminActivityIngestionService ingestionService;

    public GarminSyncService(GarminActivitySource activitySource, GarminActivityIngestionService ingestionService) {
        this.activitySource = activitySource;
        this.ingestionService = ingestionService;
    }

    /**
     * @throws GarminConnectorException when the connector or Garmin fails; nothing is ingested then
     */
    public GarminSyncResult syncRecent(int limit) {
        Instant fetchedAt = Instant.now();
        List<JsonNode> items = activitySource.fetchRecentActivities(limit);
        log.info("Garmin sync started: fetched={} limit={}", items.size(), limit);

        int created = 0;
        int updated = 0;
        int skipped = 0;
        int failed = 0;
        for (JsonNode item : items) {
            try {
                GarminIngestionResult result = ingestionService.ingest(item, fetchedAt);
                if (result.created()) {
                    created++;
                } else {
                    updated++;
                }
            } catch (GarminActivityMappingException e) {
                if (e.getReason() == Reason.UNSUPPORTED_ACTIVITY_TYPE) {
                    skipped++;
                    log.info("Garmin sync skipped unsupported activity: externalId={} code={}",
                            e.getGarminActivityId(), e.getCode());
                } else {
                    failed++;
                    log.warn("Garmin sync failed to ingest activity: externalId={} code={}",
                            e.getGarminActivityId(), e.getCode());
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("Garmin sync failed to ingest activity: {}", e.getClass().getSimpleName(), e);
            }
        }
        GarminSyncResult result = new GarminSyncResult(items.size(), created, updated, skipped, failed);
        log.info("Garmin sync completed: fetched={} created={} updated={} skipped={} failed={}",
                result.fetched(), result.created(), result.updated(), result.skipped(), result.failed());
        return result;
    }
}
