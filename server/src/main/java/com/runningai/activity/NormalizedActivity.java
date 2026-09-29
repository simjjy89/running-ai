package com.runningai.activity;

import java.time.Instant;
import java.util.Objects;

/**
 * Source-independent activity data produced by an integration mapper and
 * consumed by {@link ActivityService#upsertExternalActivity(NormalizedActivity)}.
 * Timestamps are UTC instants; distance is metres; duration is whole seconds.
 */
public record NormalizedActivity(
        ExternalSource externalSource,
        String externalId,
        ActivityType activityType,
        Instant startedAt,
        int durationSeconds,
        Double distanceMeters,
        Integer averageHeartRate,
        Integer maxHeartRate
) {

    public NormalizedActivity {
        Objects.requireNonNull(externalSource, "externalSource");
        Objects.requireNonNull(externalId, "externalId");
        Objects.requireNonNull(activityType, "activityType");
        Objects.requireNonNull(startedAt, "startedAt");
        if (externalId.isBlank()) {
            throw new IllegalArgumentException("externalId must not be blank");
        }
        if (durationSeconds < 0) {
            throw new IllegalArgumentException("durationSeconds must be >= 0");
        }
    }
}
