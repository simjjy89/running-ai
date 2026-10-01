package com.runningai.integration.intervals;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on Spring scheduling for workout publishing only when its scheduler is explicitly enabled. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "running-ai.workout-publishing.scheduler", name = "enabled", havingValue = "true")
public class WorkoutPublishingSchedulingConfig {
}
