package com.runningai.integration.garmin;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Garmin recovery sync (Phase 6F). Manual only: there is no scheduler, so starting the server never
 * calls Garmin for recovery data.
 *
 * @param backfillDelay pause between two days of a backfill. Each day costs five sequential Garmin
 *                      calls; the pause keeps a 28-day backfill gentle on Garmin's rate limit
 * @param maxBackfillDays largest backfill a single request may ask for
 */
@ConfigurationProperties(prefix = "running-ai.garmin.recovery-sync")
public record GarminRecoverySyncProperties(
        @DefaultValue("2s") Duration backfillDelay,
        @DefaultValue("28") int maxBackfillDays
) {
}
