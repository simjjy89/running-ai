package com.runningai.integration.garmin;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Where the localhost Garmin connector listens. Deliberately holds no credentials:
 * Garmin authentication is the connector's job.
 */
@Validated
@ConfigurationProperties(prefix = "running-ai.garmin.connector")
public record GarminConnectorProperties(
        @NotBlank String baseUrl,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("30s") Duration readTimeout
) {
}
