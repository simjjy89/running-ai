package com.runningai.integration.garmin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * Test helper: loads synthetic Garmin payloads from {@code fixtures/garmin/}.
 */
final class GarminFixtures {

    static final String RUNNING = "running-activity.json";
    static final String RUNNING_REFETCHED = "running-activity-refetched.json";
    static final String TREADMILL = "treadmill-activity.json";
    static final String INDOOR_CYCLING = "indoor-cycling-activity.json";
    static final String SWIMMING = "swimming-activity.json";
    static final String MISSING_START_TIME = "missing-start-time.json";
    static final String MISSING_ACTIVITY_ID = "missing-activity-id.json";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private GarminFixtures() {
    }

    static JsonNode load(String name) {
        String path = "fixtures/garmin/" + name;
        try (InputStream in = GarminFixtures.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException("Fixture not found on classpath: " + path);
            }
            return OBJECT_MAPPER.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read fixture " + path, e);
        }
    }

    static JsonNode json(String content) {
        try {
            return OBJECT_MAPPER.readTree(content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
