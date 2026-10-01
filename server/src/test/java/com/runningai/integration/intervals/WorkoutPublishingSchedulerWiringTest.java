package com.runningai.integration.intervals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.support.SimpleTriggerContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The scheduler switched on explicitly (scheduler only: the master switch stays at its default, off). The application
 * service is mocked, so even a tick at the configured time could not reach Intervals.
 */
@SpringBootTest(properties = {
        "running-ai.workout-publishing.scheduler.enabled=true",
        "running-ai.workout-publishing.scheduler.cron=0 0 5 * * *",
        "running-ai.workout-publishing.scheduler.zone=Asia/Tokyo",
        "running-ai.intervals.api-key=",
        "running-ai.intervals.base-url=http://127.0.0.1:9"})
@ActiveProfiles("test")
class WorkoutPublishingSchedulerWiringTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private WorkoutPublishProperties properties;

    @Autowired
    private ScheduledAnnotationBeanPostProcessor postProcessor;

    @MockitoBean
    private WorkoutPublishApplicationService publishService;

    @Test
    void enabledSchedulerBeanExistsAndRegistersOneCronTaskWithTheConfiguredCronAndZone() {
        List<CronTask> tasks = postProcessor.getScheduledTasks().stream()
                .map(t -> t.getTask())
                .filter(CronTask.class::isInstance)
                .map(CronTask.class::cast)
                .filter(t -> t.toString().contains("WorkoutPublishingScheduler"))
                .toList();

        assertThat(context.getBeansOfType(WorkoutPublishingScheduler.class)).hasSize(1);
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).getExpression()).isEqualTo("0 0 5 * * *");
        // 2026-10-01T00:00Z is 09:00 in Tokyo, so the next 05:00 there is 2026-10-01T20:00Z (a UTC or Seoul zone would differ).
        Instant next = tasks.get(0).getTrigger()
                .nextExecution(new SimpleTriggerContext(Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC)));
        assertThat(next).isEqualTo(Instant.parse("2026-10-01T20:00:00Z"));
        verifyNoInteractions(publishService);
    }

    @Test
    void masterSwitchStaysOffWhenOnlyTheSchedulerIsEnabled() {
        assertThat(properties.scheduler().enabled()).isTrue();
        assertThat(properties.enabled()).isFalse();
        assertThat(properties.scheduler().zone()).isEqualTo(ZoneId.of("Asia/Tokyo"));
    }
}
