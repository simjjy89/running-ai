package com.runningai.integration.intervals;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.ZoneId;

/**
 * Operational safety switches for workout publishing.
 * <p>
 * {@code enabled} is the master switch: may RunningAI write Intervals workouts at all (manual POST and scheduler alike).
 * It is off by default because the legacy main-PC publisher may still be active (two writers on one calendar).
 * {@code scheduler.enabled} is a separate switch for the automatic daily trigger (also off by default); it never
 * bypasses the master switch, which {@link WorkoutPublishApplicationService} enforces on every call.
 */
@ConfigurationProperties(prefix = "running-ai.workout-publishing")
public record WorkoutPublishProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue Scheduler scheduler
) {

    public static final String DEFAULT_CRON = "0 0 5 * * *";
    public static final String DEFAULT_ZONE = "Asia/Seoul";

    /**
     * @param enabled whether the automatic daily trigger exists at all (the bean is only created when true)
     * @param cron    Spring six-field cron; the default is a technical default (05:00), not an operating policy
     * @param zone    zone in which the cron fires and in which "today" is decided; never the server's default zone
     */
    public record Scheduler(
            @DefaultValue("false") boolean enabled,
            @DefaultValue(DEFAULT_CRON) String cron,
            @DefaultValue(DEFAULT_ZONE) ZoneId zone
    ) {

        public static Scheduler defaults() {
            return new Scheduler(false, DEFAULT_CRON, ZoneId.of(DEFAULT_ZONE));
        }
    }
}
