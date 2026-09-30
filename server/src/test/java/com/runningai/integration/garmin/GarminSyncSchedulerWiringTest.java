package com.runningai.integration.garmin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Property wiring of the scheduler when it is explicitly enabled. The connector is mocked and
 * the initial delay is 30 minutes, so no tick runs during the test and nothing touches Garmin.
 */
@SpringBootTest(properties = {
        "running-ai.garmin.scheduler.enabled=true",
        "running-ai.garmin.scheduler.fixed-delay=2h",
        "running-ai.garmin.scheduler.initial-delay=30m"
})
@ActiveProfiles("test")
class GarminSyncSchedulerWiringTest {

    @Autowired
    private GarminSyncScheduler scheduler;

    @Autowired
    private ScheduledAnnotationBeanPostProcessor postProcessor;

    @MockitoBean
    private GarminActivitySource activitySource;

    @Test
    void enabledSchedulerRegistersOneFixedDelayTaskWithConfiguredDurations() {
        List<FixedDelayTask> tasks = postProcessor.getScheduledTasks().stream()
                .map(t -> t.getTask())
                .filter(FixedDelayTask.class::isInstance)
                .map(FixedDelayTask.class::cast)
                .filter(t -> t.toString().contains("GarminSyncScheduler"))
                .toList();

        assertThat(scheduler).isNotNull();
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).getIntervalDuration()).isEqualTo(Duration.ofHours(2));
        assertThat(tasks.get(0).getInitialDelayDuration()).isEqualTo(Duration.ofMinutes(30));
        verifyNoInteractions(activitySource);
    }

    @Test
    void environmentVariableNamesBindToTheSchedulerProperties() {
        SystemEnvironmentPropertySource env = new SystemEnvironmentPropertySource("env", Map.of(
                "RUNNING_AI_GARMIN_SCHEDULER_ENABLED", "true",
                "RUNNING_AI_GARMIN_SCHEDULER_FIXED_DELAY", "30m",
                "RUNNING_AI_GARMIN_SCHEDULER_INITIAL_DELAY", "5m"));

        assertThat(env.getProperty("running-ai.garmin.scheduler.enabled")).isEqualTo("true");
        assertThat(env.getProperty("running-ai.garmin.scheduler.fixed-delay")).isEqualTo("30m");
        assertThat(env.getProperty("running-ai.garmin.scheduler.initial-delay")).isEqualTo("5m");
    }
}
