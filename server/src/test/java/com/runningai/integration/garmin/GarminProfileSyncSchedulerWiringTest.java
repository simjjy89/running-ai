package com.runningai.integration.garmin;

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
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The profile-sync scheduler switched on explicitly, with its connector mocked, so even a tick at
 * the configured time could not reach Garmin.
 */
@SpringBootTest(properties = {
        "running-ai.garmin.profile-sync.scheduler.enabled=true",
        "running-ai.garmin.profile-sync.scheduler.cron=0 30 4 * * *",
        "running-ai.garmin.profile-sync.scheduler.zone=Asia/Tokyo"})
@ActiveProfiles("test")
class GarminProfileSyncSchedulerWiringTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ScheduledAnnotationBeanPostProcessor postProcessor;

    @MockitoBean
    private GarminLactateThresholdSource lactateThresholdSource;

    @Test
    void enabledSchedulerRegistersOneCronTaskWithTheConfiguredCronAndZone() {
        List<CronTask> tasks = postProcessor.getScheduledTasks().stream()
                .map(t -> t.getTask())
                .filter(CronTask.class::isInstance)
                .map(CronTask.class::cast)
                .filter(t -> t.toString().contains("GarminProfileSyncScheduler"))
                .toList();

        assertThat(context.getBeansOfType(GarminProfileSyncScheduler.class)).hasSize(1);
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).getExpression()).isEqualTo("0 30 4 * * *");
        // 2026-10-01T00:00Z is 09:00 in Tokyo, so the next 04:30 there is 2026-10-01T19:30Z.
        Instant next = tasks.get(0).getTrigger()
                .nextExecution(new SimpleTriggerContext(Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC)));
        assertThat(next).isEqualTo(Instant.parse("2026-10-01T19:30:00Z"));
        verifyNoInteractions(lactateThresholdSource);
    }
}
