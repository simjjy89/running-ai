package com.runningai.integration.intervals;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Operational safety switch for the manual workout publish trigger. Off by default: while the legacy
 * main-PC workout publisher can still be active, enabling this would allow two writers on one calendar.
 */
@ConfigurationProperties(prefix = "running-ai.workout-publishing")
public record WorkoutPublishProperties(@DefaultValue("false") boolean enabled) {
}
