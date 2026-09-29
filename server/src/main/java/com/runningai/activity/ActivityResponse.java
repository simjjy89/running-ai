package com.runningai.activity;

import java.time.Instant;

public record ActivityResponse(
        Long id,
        Long athleteId,
        ExternalSource externalSource,
        String externalId,
        ActivityType activityType,
        Instant startedAt,
        int durationSeconds,
        Double distanceMeters,
        Integer averageHeartRate,
        Integer maxHeartRate,
        Instant createdAt,
        Instant updatedAt
) {

    public static ActivityResponse from(Activity activity) {
        return new ActivityResponse(
                activity.getId(),
                activity.getAthleteId(),
                activity.getExternalSource(),
                activity.getExternalId(),
                activity.getActivityType(),
                activity.getStartedAt(),
                activity.getDurationSeconds(),
                activity.getDistanceMeters(),
                activity.getAverageHeartRate(),
                activity.getMaxHeartRate(),
                activity.getCreatedAt(),
                activity.getUpdatedAt()
        );
    }
}
