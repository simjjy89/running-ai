package com.runningai.activity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

public record ActivityCreateRequest(
        @NotNull ExternalSource externalSource,
        @NotBlank @Size(max = 100) String externalId,
        @NotNull ActivityType activityType,
        @NotNull OffsetDateTime startedAt,
        @NotNull @PositiveOrZero Integer durationSeconds,
        @PositiveOrZero Double distanceMeters,
        @PositiveOrZero Integer averageHeartRate,
        @PositiveOrZero Integer maxHeartRate
) {
}
