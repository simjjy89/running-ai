package com.runningai.integration.intervals;

import com.runningai.integration.garmin.GarminActivitySource;
import com.runningai.integration.intervals.IntervalsException.Reason;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The application starts without Intervals credentials; the publisher then refuses to call out. */
// The properties pin a blank key and an unreachable URL so that a developer machine that has a real
// INTERVALS_API_KEY in its environment can never make this test write to the real calendar.
@SpringBootTest(properties = {"running-ai.intervals.api-key=", "running-ai.intervals.athlete-id=0", "running-ai.intervals.base-url=http://127.0.0.1:9"})
@ActiveProfiles("test")
class IntervalsWiringTest {

    @Autowired
    private IntervalsProperties properties;

    @Autowired
    private IntervalsWorkoutPublisher publisher;

    @Autowired
    private IntervalsWorkoutClient client;

    @Autowired
    private GarminActivitySource garminSource;      // two RestClient beans must not make this ambiguous

    @Test
    void defaultsAreSafeAndHaveNoCredential() {
        assertThat(properties.baseUrl()).isEqualTo("http://127.0.0.1:9");
        assertThat(properties.athleteId()).isEqualTo("0");
        assertThat(properties.apiKey()).isBlank();
        assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(15));
        assertThat(client).isInstanceOf(HttpIntervalsWorkoutClient.class);
        assertThat(garminSource).isNotNull();
    }

    @Test
    void withoutAnApiKeyPublishFailsBeforeAnyNetworkCall() {
        assertThatThrownBy(() -> publisher.publish(LocalDate.of(2026, 10, 1), new RenderedIntervalsWorkout("- Easy 30m")))
                .isInstanceOfSatisfying(IntervalsException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(Reason.NOT_CONFIGURED);
                    assertThat(e.getCode()).isEqualTo("INTERVALS_NOT_CONFIGURED");
                });
    }
}
