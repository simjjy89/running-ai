package com.runningai.integration.garmin;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on Spring scheduling for the Garmin profile sync only when its scheduler is explicitly enabled. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "running-ai.garmin.profile-sync.scheduler", name = "enabled", havingValue = "true")
public class GarminProfileSyncSchedulingConfig {
}
