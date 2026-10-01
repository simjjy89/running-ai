package com.runningai.integration.intervals;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Environment-independent: binds from explicit maps / the shipped yaml text, never from the real process environment. */
class WorkoutPublishPropertiesTest {

    private static WorkoutPublishProperties bind(Map<String, String> source) {
        return new Binder(new MapConfigurationPropertySource(source))
                .bindOrCreate("running-ai.workout-publishing", WorkoutPublishProperties.class);
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
        String yaml = new String(new ClassPathResource("application.yml").getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(yaml).contains("enabled: ${WORKOUT_PUBLISHING_ENABLED:false}");
    }
}
