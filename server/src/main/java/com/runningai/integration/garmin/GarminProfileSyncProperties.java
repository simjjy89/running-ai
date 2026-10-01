package com.runningai.integration.garmin;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.ZoneId;

/**
 * Automatic Garmin athlete profile sync (Phase 6D). There is no master switch here (unlike
 * workout publishing): the manual trigger ({@code POST /api/v1/garmin/profile-sync}) always
 * works; only the automatic scheduler is gated, off by default, so starting the server never
 * calls Garmin on its own.
 */
@ConfigurationProperties(prefix = "running-ai.garmin.profile-sync")
public record GarminProfileSyncProperties(
        @DefaultValue Scheduler scheduler
) {

    public static final String DEFAULT_CRON = "0 30 4 * * *";
    public static final String DEFAULT_ZONE = "Asia/Seoul";

    /**
     * @param enabled whether the automatic trigger exists at all (the bean is only created when true)
     * @param cron    Spring six-field cron; default 04:30 so a sync run completes before the
     *                workout-publishing scheduler's default 05:00 run (recommended operational
     *                order: profile sync -&gt; DB -&gt; workout publish), though the two schedulers
     *                share no code, lock or dependency
     * @param zone    zone in which the cron fires; never the server's default zone
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
