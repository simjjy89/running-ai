package com.runningai.integration.garmin;

import com.fasterxml.jackson.databind.JsonNode;
import com.runningai.activity.ActivityRaw;
import com.runningai.activity.ActivityRawService;
import com.runningai.activity.ActivityService;
import com.runningai.activity.ActivityUpsertResult;
import com.runningai.activity.ExternalSource;
import com.runningai.activity.NormalizedActivity;
import com.runningai.common.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Raw-first ingestion of Garmin activity payloads.
 * <pre>
 *   Garmin JSON
 *     -> ActivityRawService.saveOrUpdate     (transaction 1, committed)
 *     -> GarminActivityMapper                (pure, may fail: raw stays)
 *     -> ActivityService.upsertExternalActivity (transaction 2, committed)
 *     -> ActivityRawService.linkToActivity   (transaction 3)
 * </pre>
 * This class is deliberately NOT transactional: wrapping the whole flow in one
 * transaction would roll the raw snapshot back when mapping fails, which is the
 * opposite of what raw-first storage is for. Each step commits on its own; if
 * the final link step fails, the next saveOrUpdate of the same activity re-links
 * automatically.
 * <p>
 * No Garmin network access lives here. Phase 3B's client will hand JsonNodes to
 * {@link #ingest(JsonNode)}.
 */
@Service
public class GarminActivityIngestionService {

    private static final Logger log = LoggerFactory.getLogger(GarminActivityIngestionService.class);

    private final ActivityRawService activityRawService;
    private final ActivityService activityService;
    private final GarminActivityMapper mapper;

    public GarminActivityIngestionService(ActivityRawService activityRawService,
                                          ActivityService activityService,
                                          GarminActivityMapper mapper) {
        this.activityRawService = activityRawService;
        this.activityService = activityService;
        this.mapper = mapper;
    }

    public GarminIngestionResult ingest(JsonNode payload) {
        return ingest(payload, Instant.now());
    }

    /**
     * Stores the payload first, then normalises it into an Activity.
     *
     * @throws GarminActivityMappingException when the payload cannot be mapped;
     *         the raw snapshot is already committed unless the activity id itself
     *         was missing (nothing to key the raw row on).
     */
    public GarminIngestionResult ingest(JsonNode payload, Instant fetchedAt) {
        String garminActivityId = mapper.extractActivityId(payload);
        log.info("Garmin activity ingestion started: externalId={}", garminActivityId);

        ActivityRaw raw = activityRawService.saveOrUpdate(ExternalSource.GARMIN, garminActivityId, payload, fetchedAt);
        log.info("Garmin raw payload stored: externalId={} rawId={}", garminActivityId, raw.getId());

        NormalizedActivity normalized = mapOrFail(payload, garminActivityId, raw.getId());
        return persistNormalized(normalized, raw.getId());
    }

    /**
     * Re-runs mapping + upsert from the stored raw payload only. Useful after a
     * mapper fix or a new normalised field; never touches Garmin.
     */
    public GarminIngestionResult reprocess(String garminActivityId) {
        ActivityRaw raw = activityRawService.find(ExternalSource.GARMIN, garminActivityId)
                .orElseThrow(() -> new ResourceNotFoundException("ACTIVITY_RAW_NOT_FOUND",
                        "No Garmin raw payload stored for activity " + garminActivityId));
        log.info("Garmin activity reprocessing started: externalId={} rawId={}", garminActivityId, raw.getId());

        NormalizedActivity normalized = mapOrFail(raw.getPayload(), garminActivityId, raw.getId());
        return persistNormalized(normalized, raw.getId());
    }

    private NormalizedActivity mapOrFail(JsonNode payload, String garminActivityId, Long rawId) {
        try {
            return mapper.map(payload);
        } catch (GarminActivityMappingException e) {
            log.warn("Garmin activity mapping failed: externalId={} reason={} rawId={} (raw payload preserved)",
                    garminActivityId, e.getCode(), rawId);
            throw e;
        }
    }

    private GarminIngestionResult persistNormalized(NormalizedActivity normalized, Long rawId) {
        ActivityUpsertResult upsert = activityService.upsertExternalActivity(normalized);
        Long activityId = upsert.activity().getId();
        activityRawService.linkToActivity(rawId, activityId);

        GarminIngestionResult.Action action = upsert.created()
                ? GarminIngestionResult.Action.CREATED
                : GarminIngestionResult.Action.UPDATED;
        log.info("Garmin activity ingestion completed: externalId={} activityId={} rawId={} action={}",
                normalized.externalId(), activityId, rawId, action);
        return new GarminIngestionResult(normalized.externalId(), activityId, rawId, action);
    }
}
