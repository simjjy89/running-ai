package com.runningai.integration.garmin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.runningai.activity.ActivityType;
import com.runningai.activity.ExternalSource;
import com.runningai.activity.NormalizedActivity;
import com.runningai.integration.garmin.GarminActivityMappingException.Reason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests: no Spring context, no database, no network.
 * Fixtures follow the Garmin Connect activity-list contract (seconds, metres,
 * startTimeGMT without zone designator) documented in the Phase 3B-1 work order.
 */
class GarminActivityMapperTest {

    private final GarminActivityMapper mapper = new GarminActivityMapper();

    private static GarminActivityMappingException mappingFailure(Runnable action) {
        try {
            action.run();
        } catch (GarminActivityMappingException e) {
            return e;
        }
        throw new AssertionError("expected GarminActivityMappingException");
    }

    private static String garminItem(String extraFields) {
        return "{\"activityId\": 1, \"activityType\": {\"typeKey\": \"running\"}, "
                + "\"startTimeGMT\": \"2026-09-28 21:30:00\", \"duration\": 1000.0" + extraFields + "}";
    }

    @Test
    void mapsRunningFixtureToRun() {
        NormalizedActivity activity = mapper.map(GarminFixtures.load(GarminFixtures.RUNNING));

        assertThat(activity.externalSource()).isEqualTo(ExternalSource.GARMIN);
        assertThat(activity.externalId()).isEqualTo("188081596");
        assertThat(activity.activityType()).isEqualTo(ActivityType.RUN);
        assertThat(activity.startedAt()).isEqualTo(Instant.parse("2026-09-28T21:30:00Z"));
        assertThat(activity.durationSeconds()).isEqualTo(3600);
        assertThat(activity.distanceMeters()).isEqualTo(10000.0);
        assertThat(activity.averageHeartRate()).isEqualTo(155);
        assertThat(activity.maxHeartRate()).isEqualTo(172);
    }

    @Test
    void mapsTreadmillFixtureToTreadmillRun() {
        NormalizedActivity activity = mapper.map(GarminFixtures.load(GarminFixtures.TREADMILL));

        assertThat(activity.externalId()).isEqualTo("188090001");
        assertThat(activity.activityType()).isEqualTo(ActivityType.TREADMILL_RUN);
        assertThat(activity.startedAt()).isEqualTo(Instant.parse("2026-09-27T10:05:30Z"));
        assertThat(activity.durationSeconds()).isEqualTo(2401);   // 2400.5 s rounds to 2401 s
        assertThat(activity.distanceMeters()).isEqualTo(8000.0);
        assertThat(activity.averageHeartRate()).isEqualTo(162);
        assertThat(activity.maxHeartRate()).isEqualTo(178);
    }

    @Test
    void mapsIndoorCyclingFixtureWithNullableMaxHeartRate() {
        NormalizedActivity activity = mapper.map(GarminFixtures.load(GarminFixtures.INDOOR_CYCLING));

        assertThat(activity.externalId()).isEqualTo("188090002");
        assertThat(activity.activityType()).isEqualTo(ActivityType.INDOOR_CYCLING);
        assertThat(activity.startedAt()).isEqualTo(Instant.parse("2026-09-28T12:00:00Z"));
        assertThat(activity.durationSeconds()).isEqualTo(3000);
        assertThat(activity.distanceMeters()).isEqualTo(25000.0);
        assertThat(activity.averageHeartRate()).isEqualTo(140);
        assertThat(activity.maxHeartRate()).isNull();
    }

    @Test
    void parsedPayloadKeepsGarminNameAndTypeKey() {
        GarminActivityPayload payload = mapper.parse(GarminFixtures.load(GarminFixtures.RUNNING));

        assertThat(payload.activityId()).isEqualTo("188081596");
        assertThat(payload.activityName()).isEqualTo("Morning Run");
        assertThat(payload.activityType()).isEqualTo("running");
        assertThat(payload.durationSeconds()).isEqualTo(3600L);
    }

    @ParameterizedTest
    @CsvSource({
            "running, RUN",
            "RUNNING, RUN",
            "run, RUN",
            "trail_running, RUN",
            "treadmill_running, TREADMILL_RUN",
            "treadmill, TREADMILL_RUN",
            "indoor_cycling, INDOOR_CYCLING",
            "indoor_biking, INDOOR_CYCLING",
            "cycling_indoor, INDOOR_CYCLING",
            "virtual_ride, INDOOR_CYCLING"
    })
    void mapsKnownGarminTypeKeys(String typeKey, ActivityType expected) {
        assertThat(mapper.mapActivityType(typeKey, "x")).isEqualTo(expected);
    }

    @Test
    void acceptsActivityTypeAsPlainString() {
        ObjectNode payload = (ObjectNode) GarminFixtures.load(GarminFixtures.RUNNING);
        payload.put("activityType", "treadmill_running");

        assertThat(mapper.map(payload).activityType()).isEqualTo(ActivityType.TREADMILL_RUN);
    }

    @Test
    void unsupportedTypeFailsInsteadOfDefaultingToRun() {
        GarminActivityMappingException e = mappingFailure(
                () -> mapper.map(GarminFixtures.load(GarminFixtures.SWIMMING)));

        assertThat(e.getReason()).isEqualTo(Reason.UNSUPPORTED_ACTIVITY_TYPE);
        assertThat(e.getCode()).isEqualTo("UNSUPPORTED_GARMIN_ACTIVITY_TYPE");
        assertThat(e.getGarminActivityId()).isEqualTo("188090003");
        assertThat(e.getMessage()).contains("lap_swimming");
    }

    @Test
    void missingActivityIdFails() {
        JsonNode payload = GarminFixtures.load(GarminFixtures.MISSING_ACTIVITY_ID);

        assertThat(mappingFailure(() -> mapper.extractActivityId(payload)).getReason())
                .isEqualTo(Reason.ACTIVITY_ID_MISSING);
        assertThat(mappingFailure(() -> mapper.map(payload)).getCode())
                .isEqualTo("GARMIN_ACTIVITY_ID_MISSING");
    }

    @Test
    void missingStartTimeFails() {
        GarminActivityMappingException e = mappingFailure(
                () -> mapper.map(GarminFixtures.load(GarminFixtures.MISSING_START_TIME)));

        assertThat(e.getReason()).isEqualTo(Reason.START_TIME_MISSING);
        assertThat(e.getGarminActivityId()).isEqualTo("188090004");
    }

    @Test
    void startTimeLocalAloneIsNotEnough() {
        // Garmin's startTimeLocal carries no zone; without startTimeGMT the instant is unknown.
        GarminActivityMappingException e = mappingFailure(() -> mapper.map(GarminFixtures.json("""
                {"activityId": 1, "activityType": {"typeKey": "running"}, "startTimeLocal": "2026-09-29 06:30:00",
                 "duration": 1000.0}
                """)));

        assertThat(e.getReason()).isEqualTo(Reason.START_TIME_MISSING);
    }

    @Test
    void missingActivityTypeFails() {
        ObjectNode payload = (ObjectNode) GarminFixtures.load(GarminFixtures.RUNNING);
        payload.remove("activityType");

        assertThat(mappingFailure(() -> mapper.map(payload)).getReason()).isEqualTo(Reason.ACTIVITY_TYPE_MISSING);
    }

    @Test
    void missingDurationFails() {
        ObjectNode payload = (ObjectNode) GarminFixtures.load(GarminFixtures.RUNNING);
        payload.remove("duration");

        assertThat(mappingFailure(() -> mapper.map(payload)).getReason()).isEqualTo(Reason.DURATION_MISSING);
    }

    @Test
    void invalidStartTimeFails() {
        ObjectNode payload = (ObjectNode) GarminFixtures.load(GarminFixtures.RUNNING);
        payload.put("startTimeGMT", "yesterday morning");

        assertThat(mappingFailure(() -> mapper.map(payload)).getReason()).isEqualTo(Reason.START_TIME_INVALID);
    }

    @Test
    void garminStartTimeGmtWithoutZoneDesignatorIsUtc() {
        assertThat(mapper.map(GarminFixtures.json(garminItem(""))).startedAt())
                .isEqualTo(Instant.parse("2026-09-28T21:30:00Z"));
    }

    @Test
    void startTimeGmtWinsOverLocalTimeAndOtherForms() {
        NormalizedActivity activity = mapper.map(GarminFixtures.json("""
                {"activityId": 1, "activityType": {"typeKey": "running"},
                 "startTimeGMT": "2026-09-28 21:30:00", "startTimeLocal": "2026-09-29 06:30:00",
                 "startTime": "2000-01-01T00:00:00Z", "timeZoneId": "UTC", "duration": 1000.0}
                """));

        assertThat(activity.startedAt()).isEqualTo(Instant.parse("2026-09-28T21:30:00Z"));
    }

    @Test
    void isoOffsetStartTimeIsAcceptedAsFallback() {
        assertThat(mapper.map(GarminFixtures.json("""
                {"activityId": 1, "activityType": "running", "startTime": "2026-09-29T06:30:00+09:00", "duration": 1000}
                """)).startedAt()).isEqualTo(Instant.parse("2026-09-28T21:30:00Z"));
    }

    @Test
    void localStartTimeWithTimeZoneIdIsAcceptedAsFallback() {
        assertThat(mapper.map(GarminFixtures.json("""
                {"activityId": 1, "activityType": "running", "startTimeLocal": "2026-09-29 06:30:00",
                 "timeZoneId": "Asia/Seoul", "duration": 1000}
                """)).startedAt()).isEqualTo(Instant.parse("2026-09-28T21:30:00Z"));
    }

    @ParameterizedTest
    @CsvSource({
            "3600.0, 3600",
            "3612.0, 3612",
            "2400.5, 2401",
            "2400.4, 2400",
            "3600, 3600",
            "0, 0"
    })
    void durationIsGarminSecondsRoundedToWholeSeconds(String duration, int expectedSeconds) {
        NormalizedActivity activity = mapper.map(GarminFixtures.json(
                "{\"activityId\": 1, \"activityType\": {\"typeKey\": \"running\"}, "
                        + "\"startTimeGMT\": \"2026-09-28 21:30:00\", \"duration\": " + duration + "}"));

        assertThat(activity.durationSeconds()).isEqualTo(expectedSeconds);
    }

    @Test
    void distanceIsMetresAndHeartRatesAreRoundedBpm() {
        NormalizedActivity activity = mapper.map(GarminFixtures.json(garminItem(
                ", \"distance\": 10023.45, \"averageHR\": 154.6, \"maxHR\": 172.0")));

        assertThat(activity.distanceMeters()).isEqualTo(10023.45);
        assertThat(activity.averageHeartRate()).isEqualTo(155);
        assertThat(activity.maxHeartRate()).isEqualTo(172);
    }

    @Test
    void absentOptionalMetricsBecomeNullNotZero() {
        NormalizedActivity activity = mapper.map(GarminFixtures.json(garminItem(", \"averageHR\": null")));

        assertThat(activity.distanceMeters()).isNull();
        assertThat(activity.averageHeartRate()).isNull();
        assertThat(activity.maxHeartRate()).isNull();
    }

    @Test
    void negativeMetricFails() {
        assertThat(mappingFailure(() -> mapper.map(GarminFixtures.json(garminItem(", \"distance\": -5"))))
                .getReason()).isEqualTo(Reason.INVALID_VALUE);
    }
}
