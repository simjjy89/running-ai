package com.runningai.common.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Application-level settings bound from the {@code running-ai.*} namespace.
 */
@Validated
@ConfigurationProperties(prefix = "running-ai")
public record RunningAiProperties(@Valid DefaultAthlete defaultAthlete) {

    /**
     * The athlete that owns activities when no athlete is specified.
     * RunningAI is currently a single-user service; this keeps the data model
     * multi-athlete-ready without forcing callers to pass an athlete id.
     */
    public record DefaultAthlete(@NotBlank String name, @NotBlank String timezone) {
    }
}
