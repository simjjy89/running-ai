package com.runningai.integration.garmin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** Default and test profile: nothing is scheduled, so starting the server can never call Garmin for profile sync. */
@SpringBootTest
@ActiveProfiles("test")
class GarminProfileSyncSchedulerDisabledTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void schedulerBeanAndItsSchedulingConfigAreAbsent() {
        assertThat(context.getBeansOfType(GarminProfileSyncScheduler.class)).isEmpty();
        assertThat(context.getBeansOfType(GarminProfileSyncSchedulingConfig.class)).isEmpty();
    }

    @Test
    void theManualTriggerStillExistsIndependentlyOfTheSchedulerSwitch() {
        assertThat(context.getBeansOfType(GarminAthleteProfileSyncService.class)).hasSize(1);
        assertThat(context.getBeansOfType(GarminProfileSyncController.class)).hasSize(1);
    }
}
