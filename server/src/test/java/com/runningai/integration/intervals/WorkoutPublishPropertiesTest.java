package com.runningai.integration.intervals;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Environment-independent: binds from explicit maps / the shipped yaml text, never from the real process environment. */
class WorkoutPublishPropertiesTest {

    private static WorkoutPublishProperties bind(Map<String, String> source) {
        return new Binder(new MapConfigurationPropertySource(source))
                .bindOrCreate("running-ai.workout-publishing", WorkoutPublishProperties.class);
    }

    private static String yaml() throws Exception {
        return new String(new ClassPathResource("application.yml").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    void isDisabledByDefault() {
        assertThat(bind(Map.of()).enabled()).isFalse();
    }

    @Test
    void canBeEnabledExplicitly() {
        assertThat(bind(Map.of("running-ai.workout-publishing.enabled", "true")).enabled()).isTrue();
        assertThat(bind(Map.of("running-ai.workout-publishing.enabled", "false")).enabled()).isFalse();
    }

    @Test
    void shippedApplicationYamlDefaultsTheEnvironmentOverrideToFalse() throws Exception {
        assertThat(yaml()).contains("enabled: ${WORKOUT_PUBLISHING_ENABLED:false}");
    }

    // ---- scheduler ----------------------------------------------------------------------------------------------------

    @Test
    void schedulerDefaultsAreOffAtFiveInSeoul() {
        WorkoutPublishProperties.Scheduler scheduler = bind(Map.of()).scheduler();

        assertThat(scheduler.enabled()).isFalse();
        assertThat(scheduler.cron()).isEqualTo("0 0 5 * * *");
        assertThat(scheduler.zone()).isEqualTo(ZoneId.of("Asia/Seoul"));
    }

    @Test
    void schedulerSwitchIsIndependentOfTheMasterSwitch() {
        WorkoutPublishProperties schedulerOnly = bind(Map.of("running-ai.workout-publishing.scheduler.enabled", "true"));
        WorkoutPublishProperties masterOnly = bind(Map.of("running-ai.workout-publishing.enabled", "true"));

        assertThat(schedulerOnly.scheduler().enabled()).isTrue();
        assertThat(schedulerOnly.enabled()).isFalse();
        assertThat(masterOnly.enabled()).isTrue();
        assertThat(masterOnly.scheduler().enabled()).isFalse();
    }

    @Test
    void cronAndZoneAreConfigurable() {
        WorkoutPublishProperties.Scheduler scheduler = bind(Map.of(
                "running-ai.workout-publishing.scheduler.cron", "0 30 6 * * *",
                "running-ai.workout-publishing.scheduler.zone", "UTC")).scheduler();

        assertThat(scheduler.cron()).isEqualTo("0 30 6 * * *");
        assertThat(scheduler.zone()).isEqualTo(ZoneId.of("UTC"));
    }

    @Test
    void anInvalidZoneFailsAtBindTime() {
        assertThatThrownBy(() -> bind(Map.of("running-ai.workout-publishing.scheduler.zone", "Mars/Olympus")))
                .isInstanceOf(BindException.class);
    }

    @Test
    void shippedApplicationYamlKeepsTheSchedulerOffWithTheDocumentedDefaults() throws Exception {
        String yaml = yaml();

        assertThat(yaml).contains("enabled: ${WORKOUT_PUBLISHING_SCHEDULER_ENABLED:false}");
        assertThat(yaml).contains("cron: ${WORKOUT_PUBLISHING_SCHEDULER_CRON:0 0 5 * * *}");
        assertThat(yaml).contains("zone: ${WORKOUT_PUBLISHING_SCHEDULER_ZONE:Asia/Seoul}");
    }
}
