package com.runningai.integration.garmin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** Default and test profile: nothing is scheduled, so starting the server can never call Garmin. */
@SpringBootTest
@ActiveProfiles("test")
class GarminSyncSchedulerDisabledTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void schedulerBeanAndSchedulingInfrastructureAreAbsent() {
        assertThat(context.getBeansOfType(GarminSyncScheduler.class)).isEmpty();
        assertThat(context.getBeansOfType(GarminSyncSchedulingConfig.class)).isEmpty();
        assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
    }
}
