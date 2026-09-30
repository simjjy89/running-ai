package com.runningai.integration.garmin;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on Spring scheduling only when the Garmin scheduler is explicitly enabled. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "running-ai.garmin.scheduler", name = "enabled", havingValue = "true")
public class GarminSyncSchedulingConfig {
}
