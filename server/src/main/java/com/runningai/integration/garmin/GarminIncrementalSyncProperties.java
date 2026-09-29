package com.runningai.integration.garmin;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Tuning for {@link GarminIncrementalSyncService}. {@code overlap} is re-fetched
 * behind the high-water mark on every incremental sync so that Garmin activities
 * edited after upload are still picked up.
 */
@Validated
@ConfigurationProperties(prefix = "running-ai.garmin.sync")
public record GarminIncrementalSyncProperties(
        @DefaultValue("50") int pageSize,
        @DefaultValue("7d") Duration overlap,
        @DefaultValue("10") int maxPages
) {
}
