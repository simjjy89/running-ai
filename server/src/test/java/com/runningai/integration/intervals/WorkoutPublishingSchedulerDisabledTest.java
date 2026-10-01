package com.runningai.integration.intervals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** With the scheduler switch off (pinned, so a developer's environment cannot change this test) no scheduler exists. */
@SpringBootTest(properties = {"running-ai.workout-publishing.scheduler.enabled=false", "running-ai.intervals.api-key=",
        "running-ai.intervals.base-url=http://127.0.0.1:9"})
@ActiveProfiles("test")
class WorkoutPublishingSchedulerDisabledTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void schedulerBeanAndItsSchedulingConfigAreAbsent() {
        assertThat(context.getBeansOfType(WorkoutPublishingScheduler.class)).isEmpty();
        assertThat(context.getBeansOfType(WorkoutPublishingSchedulingConfig.class)).isEmpty();
    }

    @Test
    void theManualTriggerStillExistsIndependentlyOfTheSchedulerSwitch() {
        assertThat(context.getBeansOfType(WorkoutPublishApplicationService.class)).hasSize(1);
        assertThat(context.getBeansOfType(WorkoutPublishController.class)).hasSize(1);
    }
}
