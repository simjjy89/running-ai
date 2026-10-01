package com.runningai.integration.intervals;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/**
 * Daily automatic trigger: once per cron tick it asks {@link WorkoutPublishApplicationService} to publish "today".
 * Only present when {@code running-ai.workout-publishing.scheduler.enabled=true} (default off).
 * <p>
 * It is a trigger and nothing else: it never touches the prescription source, mapper, renderer, publisher or
 * client, and adds no lock, so the application service's per-date single-flight guard also covers a tick that
 * overlaps a manual POST. "Today" is {@code LocalDate.now} in the configured zone (default Asia/Seoul), derived
 * from the shared {@link Clock}, never from the server's default time zone. The master switch is not bypassed:
 * with {@code running-ai.workout-publishing.enabled=false} each tick is refused by the service and logged.
 * <p>
 * There is no retry and no catch-up: a failed tick is logged and ends; a missed tick (server down at the cron time) is
 * not replayed. Retrying belongs to a later operating policy (the publisher already resolves unknown create
 * outcomes by lookup). Nothing thrown here can kill the scheduler thread; only the date, operation and error codes
 * are logged (never credentials, workout text or physiological values).
 */
@Component
@ConditionalOnProperty(prefix = "running-ai.workout-publishing.scheduler", name = "enabled", havingValue = "true")
public class WorkoutPublishingScheduler {

    private static final Logger log = LoggerFactory.getLogger(WorkoutPublishingScheduler.class);

    private final WorkoutPublishApplicationService publishService;
    private final WorkoutPublishProperties properties;
    private final Clock clock;

    public WorkoutPublishingScheduler(WorkoutPublishApplicationService publishService,
                                      WorkoutPublishProperties properties, Clock clock) {
        this.publishService = publishService;
        this.properties = properties;
        this.clock = clock;
        log.info("Workout publishing scheduler active: cron='{}' zone={} masterEnabled={}",
                properties.scheduler().cron(), properties.scheduler().zone(), properties.enabled());
    }

    @Scheduled(cron = "${running-ai.workout-publishing.scheduler.cron:" + WorkoutPublishProperties.DEFAULT_CRON + "}",
            zone = "${running-ai.workout-publishing.scheduler.zone:" + WorkoutPublishProperties.DEFAULT_ZONE + "}")
    public void runScheduledPublish() {
        LocalDate today = LocalDate.now(clock.withZone(properties.scheduler().zone()));
        try {
            WorkoutPublishResponse r = publishService.publish(today);
            log.info("Workout publish scheduled run completed: date={} operation={} verified={} intent={} stepCount={}",
                    r.date(), r.operation(), r.verified(), r.intent(), r.stepCount());
        } catch (WorkoutPublishAlreadyRunningException e) {
            log.info("Workout publish scheduled run SKIPPED_ALREADY_RUNNING: date={}; no retry", today);
        } catch (WorkoutPublishDisabledException e) {
            log.warn("Workout publish scheduled run refused: date={} errorCode=WORKOUT_PUBLISHING_DISABLED "
                    + "(scheduler is on but the master switch is off); no retry", today);
        } catch (IntervalsException e) {
            log.warn("Workout publish scheduled run failed: date={} errorCode={} httpStatus={}; no retry",
                    today, e.getCode(), e.getHttpStatus());
        } catch (RuntimeException e) {
            log.error("Workout publish scheduled run failed unexpectedly: date={} exception={}; no retry",
                    today, e.getClass().getSimpleName());
        }
    }
}
