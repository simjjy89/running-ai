package com.runningai.activity;

import com.fasterxml.jackson.databind.JsonNode;
import com.runningai.common.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Internal application service that stores raw external payloads. It is not
 * exposed through the HTTP API; the ingestion services call it.
 */
@Service
@Transactional(readOnly = true)
public class ActivityRawService {

    private final ActivityRawRepository activityRawRepository;
    private final ActivityRepository activityRepository;

    public ActivityRawService(ActivityRawRepository activityRawRepository, ActivityRepository activityRepository) {
        this.activityRawRepository = activityRawRepository;
        this.activityRepository = activityRepository;
    }

    /**
     * Stores the payload for an external activity, or replaces the existing
     * snapshot (payload + fetchedAt) when one is already stored for the same
     * {@code externalSource + externalId}. If a normalised {@link Activity} with
     * the same external identity already exists, the raw row is linked to it.
     */
    @Transactional
    public ActivityRaw saveOrUpdate(ExternalSource externalSource, String externalId, JsonNode payload, Instant fetchedAt) {
        ActivityRaw raw = activityRawRepository.findByExternalSourceAndExternalId(externalSource, externalId)
                .map(existing -> {
                    existing.refresh(payload, fetchedAt);
                    return existing;
                })
                .orElseGet(() -> activityRawRepository.save(new ActivityRaw(externalSource, externalId, payload, fetchedAt)));

        if (raw.getActivity() == null) {
            activityRepository.findByExternalSourceAndExternalId(externalSource, externalId).ifPresent(raw::linkTo);
        }
        return raw;
    }

    /**
     * Links a stored raw payload to its normalised activity (no-op when already
     * linked to that activity). Used once the activity exists after a raw-first
     * ingestion.
     */
    @Transactional
    public void linkToActivity(Long activityRawId, Long activityId) {
        ActivityRaw raw = activityRawRepository.findById(activityRawId)
                .orElseThrow(() -> new ResourceNotFoundException("ACTIVITY_RAW_NOT_FOUND",
                        "Activity raw not found: " + activityRawId));
        if (raw.getActivity() != null && activityId.equals(raw.getActivity().getId())) {
            return;
        }
        Activity activity = activityRepository.findById(activityId)
                .orElseThrow(() -> new ResourceNotFoundException("ACTIVITY_NOT_FOUND",
                        "Activity not found: " + activityId));
        raw.linkTo(activity);
    }

    public Optional<ActivityRaw> find(ExternalSource externalSource, String externalId) {
        return activityRawRepository.findByExternalSourceAndExternalId(externalSource, externalId);
    }
}
