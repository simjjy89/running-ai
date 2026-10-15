package com.runningai.integration.garmin;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Thresholds for {@link GarminSyncCircuitBreaker} (Phase 8B). Every default is deliberately
 * generous: the breaker exists to stop a loop that is provably going nowhere, and a breaker that
 * trips on a single bad night would be a worse problem than the one it solves.
 *
 * @param enabled             master switch; false restores the pre-8B behaviour exactly - every
 *                            scheduled tick runs, however many have failed before it
 * @param authFailureThreshold consecutive AUTH failures after which scheduled ticks stop. 401/403
 *                            never heals on its own, so this is low on purpose
 * @param rateLimitFailureThreshold consecutive RATE_LIMIT failures after which ticks pause. 1 means
 *                            "back off the moment Garmin says to"
 * @param transientFailureThreshold consecutive TRANSIENT failures after which ticks pause. High,
 *                            because a connector restart or a flaky night must not stop collection
 * @param partialThreshold    consecutive PARTIAL runs after which ticks pause. A PARTIAL holds the
 *                            checkpoint back, so repeating it re-reads the same window and re-hits
 *                            the same unmappable item; past this count a human needs to look
 * @param rateLimitCooldown   how long ticks pause after the rate-limit threshold is reached
 * @param transientCooldown   how long ticks pause after the transient threshold is reached
 * @param partialCooldown     how long ticks pause after the partial threshold is reached
 * @param minimumInterval     hard floor between two scheduled attempts, independent of outcome. A
 *                            misconfigured fixed-delay can therefore never turn into a request
 *                            flood; it only ever makes ticks arrive and be skipped
 */
@ConfigurationProperties(prefix = "running-ai.garmin.sync.circuit-breaker")
public record GarminSyncCircuitBreakerProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("3") int authFailureThreshold,
        @DefaultValue("1") int rateLimitFailureThreshold,
        @DefaultValue("8") int transientFailureThreshold,
        @DefaultValue("6") int partialThreshold,
        @DefaultValue("6h") Duration rateLimitCooldown,
        @DefaultValue("2h") Duration transientCooldown,
        @DefaultValue("12h") Duration partialCooldown,
        @DefaultValue("15m") Duration minimumInterval
) {

    public GarminSyncCircuitBreakerProperties {
        requirePositive(authFailureThreshold, "authFailureThreshold");
        requirePositive(rateLimitFailureThreshold, "rateLimitFailureThreshold");
        requirePositive(transientFailureThreshold, "transientFailureThreshold");
        requirePositive(partialThreshold, "partialThreshold");
        requireNonNegative(rateLimitCooldown, "rateLimitCooldown");
        requireNonNegative(transientCooldown, "transientCooldown");
        requireNonNegative(partialCooldown, "partialCooldown");
        requireNonNegative(minimumInterval, "minimumInterval");
    }

    private static void requirePositive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(
                    "running-ai.garmin.sync.circuit-breaker." + name + " must be at least 1 (was " + value + ")");
        }
    }

    private static void requireNonNegative(Duration value, String name) {
        if (value == null || value.isNegative()) {
            throw new IllegalArgumentException(
                    "running-ai.garmin.sync.circuit-breaker." + name + " must not be negative (was " + value + ")");
        }
    }
}
