package com.runningai.integration.garmin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure mapping tests: synthetic payloads shaped like the live-probed Phase 6D-0 contract. */
class GarminLactateThresholdMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private JsonNode parse(String json) throws Exception {
        return objectMapper.readTree(json);
    }

    @Test
    void mapsFullSnapshot() throws Exception {
        JsonNode raw = parse("""
                {"speed_and_heart_rate": {"heartRate": 180, "speed": 0.34444348},
                 "power": {"functionalThresholdPower": 300}}
                """);

        GarminLactateThresholdSnapshot snapshot = GarminLactateThresholdMapper.map(raw);

        assertThat(snapshot.lactateThresholdHeartRateBpm()).isEqualTo(180);
        assertThat(snapshot.lactateThresholdPaceSecondsPerKm()).isEqualTo(290);
    }

    @Test
    void nullHeartRateAndSpeedBecomeNullFields() throws Exception {
        JsonNode raw = parse("""
                {"speed_and_heart_rate": {"heartRate": null, "speed": null}, "power": {}}
                """);

        GarminLactateThresholdSnapshot snapshot = GarminLactateThresholdMapper.map(raw);

        assertThat(snapshot.lactateThresholdHeartRateBpm()).isNull();
        assertThat(snapshot.lactateThresholdPaceSecondsPerKm()).isNull();
    }

    @Test
    void missingSpeedAndHeartRateObjectBecomesEmptySnapshot() throws Exception {
        JsonNode raw = parse("{\"power\": {}}");

        GarminLactateThresholdSnapshot snapshot = GarminLactateThresholdMapper.map(raw);

        assertThat(snapshot.lactateThresholdHeartRateBpm()).isNull();
        assertThat(snapshot.lactateThresholdPaceSecondsPerKm()).isNull();
    }

    @Test
    void nullRootBecomesEmptySnapshot() {
        GarminLactateThresholdSnapshot snapshot = GarminLactateThresholdMapper.map(null);

        assertThat(snapshot).isEqualTo(GarminLactateThresholdSnapshot.EMPTY);
    }

    @Test
    void malformedHeartRateIsIgnoredButSpeedStillMaps() throws Exception {
        JsonNode raw = parse("""
                {"speed_and_heart_rate": {"heartRate": "not-a-number", "speed": 0.34444348}}
                """);

        GarminLactateThresholdSnapshot snapshot = GarminLactateThresholdMapper.map(raw);

        assertThat(snapshot.lactateThresholdHeartRateBpm()).isNull();
        assertThat(snapshot.lactateThresholdPaceSecondsPerKm()).isEqualTo(290);
    }

    @Test
    void malformedSpeedIsIgnoredButHeartRateStillMaps() throws Exception {
        JsonNode raw = parse("""
                {"speed_and_heart_rate": {"heartRate": 180, "speed": "not-a-number"}}
                """);

        GarminLactateThresholdSnapshot snapshot = GarminLactateThresholdMapper.map(raw);

        assertThat(snapshot.lactateThresholdHeartRateBpm()).isEqualTo(180);
        assertThat(snapshot.lactateThresholdPaceSecondsPerKm()).isNull();
    }

    @Test
    void nonPositiveHeartRateIsIgnored() throws Exception {
        JsonNode raw = parse("""
                {"speed_and_heart_rate": {"heartRate": 0, "speed": 0.34444348}}
                """);

        GarminLactateThresholdSnapshot snapshot = GarminLactateThresholdMapper.map(raw);

        assertThat(snapshot.lactateThresholdHeartRateBpm()).isNull();
    }

    @Test
    void powerObjectIsIgnoredEntirely() throws Exception {
        JsonNode raw = parse("""
                {"speed_and_heart_rate": {"heartRate": 180, "speed": 0.34444348},
                 "power": {"functionalThresholdPower": 999999}}
                """);

        GarminLactateThresholdSnapshot snapshot = GarminLactateThresholdMapper.map(raw);

        assertThat(snapshot.lactateThresholdHeartRateBpm()).isEqualTo(180);
        assertThat(snapshot.lactateThresholdPaceSecondsPerKm()).isEqualTo(290);
    }
}
