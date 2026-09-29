package com.runningai.integration.garmin;

import com.fasterxml.jackson.databind.JsonNode;
import com.runningai.activity.Activity;
import com.runningai.activity.ActivityRaw;
import com.runningai.activity.ActivityRawRepository;
import com.runningai.activity.ActivityRawService;
import com.runningai.activity.ActivityRepository;
import com.runningai.activity.ActivityType;
import com.runningai.activity.ExternalSource;
import com.runningai.common.exception.ResourceNotFoundException;
import com.runningai.integration.garmin.GarminActivityMappingException.Reason;
import com.runningai.integration.garmin.GarminIngestionResult.Action;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ingestion flow tests against the real services, repositories and the
 * Flyway-managed H2 schema.
 * <p>
 * Deliberately NOT {@code @Transactional}: the raw-first guarantee is about
 * transaction boundaries (raw committed even though mapping fails later), so a
 * test-managed rollback transaction would hide exactly what is under test. Rows
 * are removed after each test instead.
 */
@SpringBootTest
@ActiveProfiles("test")
class GarminActivityIngestionServiceTest {

    private static final Instant FIRST_FETCH = Instant.parse("2026-09-29T00:00:00Z");
    private static final Instant SECOND_FETCH = Instant.parse("2026-09-29T06:00:00Z");

    @Autowired
    private GarminActivityIngestionService ingestionService;

    @Autowired
    private ActivityRawService activityRawService;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private ActivityRawRepository activityRawRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @AfterEach
    void cleanUp() {
        activityRawRepository.deleteAll();
        activityRepository.deleteAll();
    }

    /** Reads raw + its linked activity id inside one transaction (lazy association). */
    private Long linkedActivityId(String garminActivityId) {
        return transactionTemplate.execute(status -> activityRawService.find(ExternalSource.GARMIN, garminActivityId)
                .map(ActivityRaw::getActivity)
                .map(Activity::getId)
                .orElse(null));
    }

    private ActivityRaw raw(String garminActivityId) {
        return activityRawService.find(ExternalSource.GARMIN, garminActivityId).orElseThrow();
    }

    private Activity activity(String garminActivityId) {
        return activityRepository.findByExternalSourceAndExternalId(ExternalSource.GARMIN, garminActivityId).orElseThrow();
    }

    @Test
    void firstIngestionStoresRawCreatesActivityAndLinksThem() {
        GarminIngestionResult result = ingestionService.ingest(GarminFixtures.load(GarminFixtures.RUNNING), FIRST_FETCH);

        assertThat(result.action()).isEqualTo(Action.CREATED);
        assertThat(result.garminActivityId()).isEqualTo("188081596");
        assertThat(activityRepository.count()).isEqualTo(1);
        assertThat(activityRawRepository.count()).isEqualTo(1);

        Activity activity = activity("188081596");
        assertThat(activity.getId()).isEqualTo(result.activityId());
        assertThat(activity.getActivityType()).isEqualTo(ActivityType.RUN);
        assertThat(activity.getStartedAt()).isEqualTo(Instant.parse("2026-09-28T21:30:00Z"));
        assertThat(activity.getDurationSeconds()).isEqualTo(3600);
        assertThat(activity.getDistanceMeters()).isEqualTo(10000.0);
        assertThat(activity.getAverageHeartRate()).isEqualTo(155);
        assertThat(activity.getMaxHeartRate()).isEqualTo(172);

        ActivityRaw raw = raw("188081596");
        assertThat(raw.getId()).isEqualTo(result.activityRawId());
        assertThat(raw.getFetchedAt()).isEqualTo(FIRST_FETCH);
        assertThat(raw.getPayload().get("activityName").asText()).isEqualTo("Morning Run");
        assertThat(raw.getPayload().get("laps")).hasSize(2);   // full payload kept, not just mapped fields
        assertThat(linkedActivityId("188081596")).isEqualTo(activity.getId());
    }

    @Test
    void ingestsEachSupportedActivityType() {
        ingestionService.ingest(GarminFixtures.load(GarminFixtures.RUNNING));
        ingestionService.ingest(GarminFixtures.load(GarminFixtures.TREADMILL));
        ingestionService.ingest(GarminFixtures.load(GarminFixtures.INDOOR_CYCLING));

        assertThat(activityRepository.count()).isEqualTo(3);
        assertThat(activity("188081596").getActivityType()).isEqualTo(ActivityType.RUN);
        assertThat(activity("188090001").getActivityType()).isEqualTo(ActivityType.TREADMILL_RUN);
        assertThat(activity("188090002").getActivityType()).isEqualTo(ActivityType.INDOOR_CYCLING);
        assertThat(activity("188090002").getMaxHeartRate()).isNull();
    }

    @Test
    void sameGarminActivityIngestedTwiceIsIdempotent() {
        JsonNode payload = GarminFixtures.load(GarminFixtures.RUNNING);

        GarminIngestionResult first = ingestionService.ingest(payload, FIRST_FETCH);
        GarminIngestionResult second = ingestionService.ingest(payload, SECOND_FETCH);

        assertThat(first.action()).isEqualTo(Action.CREATED);
        assertThat(second.action()).isEqualTo(Action.UPDATED);
        assertThat(second.activityId()).isEqualTo(first.activityId());
        assertThat(second.activityRawId()).isEqualTo(first.activityRawId());
        assertThat(activityRepository.count()).isEqualTo(1);
        assertThat(activityRawRepository.count()).isEqualTo(1);
        assertThat(raw("188081596").getFetchedAt()).isEqualTo(SECOND_FETCH);
    }

    @Test
    void changedPayloadUpdatesActivityAndRawInPlace() {
        GarminIngestionResult first = ingestionService.ingest(GarminFixtures.load(GarminFixtures.RUNNING), FIRST_FETCH);
        Activity before = activity("188081596");
        ActivityRaw rawBefore = raw("188081596");

        GarminIngestionResult second = ingestionService.ingest(
                GarminFixtures.load(GarminFixtures.RUNNING_REFETCHED), SECOND_FETCH);

        assertThat(second.action()).isEqualTo(Action.UPDATED);
        assertThat(activityRepository.count()).isEqualTo(1);
        assertThat(activityRawRepository.count()).isEqualTo(1);

        Activity after = activity("188081596");
        assertThat(after.getId()).isEqualTo(first.activityId()).isEqualTo(before.getId());
        assertThat(after.getDurationSeconds()).isEqualTo(3612);
        assertThat(after.getDistanceMeters()).isEqualTo(10020.0);
        assertThat(after.getAverageHeartRate()).isEqualTo(156);
        assertThat(after.getMaxHeartRate()).isEqualTo(173);
        assertThat(after.getCreatedAt()).isEqualTo(before.getCreatedAt());
        assertThat(after.getUpdatedAt()).isAfter(before.getUpdatedAt());

        ActivityRaw rawAfter = raw("188081596");
        assertThat(rawAfter.getId()).isEqualTo(first.activityRawId()).isEqualTo(rawBefore.getId());
        assertThat(rawAfter.getCreatedAt()).isEqualTo(rawBefore.getCreatedAt());
        assertThat(rawAfter.getFetchedAt()).isEqualTo(SECOND_FETCH);
        assertThat(rawAfter.getPayload().get("activityName").asText()).isEqualTo("Morning Run (corrected)");
        assertThat(rawAfter.getPayload().get("laps")).hasSize(3);
        assertThat(linkedActivityId("188081596")).isEqualTo(after.getId());
    }

    @Test
    void unsupportedActivityTypePreservesRawWithoutCreatingActivity() {
        assertThatThrownBy(() -> ingestionService.ingest(GarminFixtures.load(GarminFixtures.SWIMMING), FIRST_FETCH))
                .isInstanceOf(GarminActivityMappingException.class)
                .extracting(e -> ((GarminActivityMappingException) e).getReason())
                .isEqualTo(Reason.UNSUPPORTED_ACTIVITY_TYPE);

        assertThat(activityRepository.count()).isZero();
        assertThat(activityRawRepository.count()).isEqualTo(1);
        ActivityRaw raw = raw("188090003");
        assertThat(raw.getPayload().at("/activityType/typeKey").asText()).isEqualTo("lap_swimming");
        assertThat(linkedActivityId("188090003")).isNull();
    }

    @Test
    void missingStartTimePreservesRawWithoutCreatingActivity() {
        assertThatThrownBy(() -> ingestionService.ingest(GarminFixtures.load(GarminFixtures.MISSING_START_TIME), FIRST_FETCH))
                .isInstanceOf(GarminActivityMappingException.class)
                .hasMessageContaining("GARMIN_ACTIVITY_START_TIME_MISSING");

        assertThat(activityRepository.count()).isZero();
        assertThat(activityRawRepository.count()).isEqualTo(1);
        assertThat(raw("188090004").getFetchedAt()).isEqualTo(FIRST_FETCH);
    }

    @Test
    void missingActivityIdStoresNothing() {
        assertThatThrownBy(() -> ingestionService.ingest(GarminFixtures.load(GarminFixtures.MISSING_ACTIVITY_ID)))
                .isInstanceOf(GarminActivityMappingException.class)
                .hasMessageContaining("GARMIN_ACTIVITY_ID_MISSING");

        assertThat(activityRepository.count()).isZero();
        assertThat(activityRawRepository.count()).isZero();
    }

    @Test
    void reprocessCreatesActivityFromStoredRawOnly() {
        // raw stored earlier (e.g. by a previous run) without a normalised activity
        ActivityRaw stored = activityRawService.saveOrUpdate(
                ExternalSource.GARMIN, "188090001", GarminFixtures.load(GarminFixtures.TREADMILL), FIRST_FETCH);
        assertThat(activityRepository.count()).isZero();

        GarminIngestionResult result = ingestionService.reprocess("188090001");

        assertThat(result.action()).isEqualTo(Action.CREATED);
        assertThat(result.activityRawId()).isEqualTo(stored.getId());
        assertThat(activityRepository.count()).isEqualTo(1);
        assertThat(activity("188090001").getActivityType()).isEqualTo(ActivityType.TREADMILL_RUN);
        assertThat(activity("188090001").getDurationSeconds()).isEqualTo(2401);
        assertThat(linkedActivityId("188090001")).isEqualTo(result.activityId());
        assertThat(raw("188090001").getFetchedAt()).isEqualTo(FIRST_FETCH);   // reprocess is not a fetch
    }

    @Test
    void reprocessUpdatesExistingActivityFromStoredRaw() {
        GarminIngestionResult ingested = ingestionService.ingest(GarminFixtures.load(GarminFixtures.RUNNING), FIRST_FETCH);
        // simulate a later raw refresh whose mapping never ran (e.g. process crashed after raw save)
        activityRawService.saveOrUpdate(ExternalSource.GARMIN, "188081596",
                GarminFixtures.load(GarminFixtures.RUNNING_REFETCHED), SECOND_FETCH);
        assertThat(activity("188081596").getDurationSeconds()).isEqualTo(3600);

        GarminIngestionResult result = ingestionService.reprocess("188081596");

        assertThat(result.action()).isEqualTo(Action.UPDATED);
        assertThat(result.activityId()).isEqualTo(ingested.activityId());
        assertThat(activityRepository.count()).isEqualTo(1);
        assertThat(activity("188081596").getDurationSeconds()).isEqualTo(3612);
        assertThat(activity("188081596").getAverageHeartRate()).isEqualTo(156);
    }

    @Test
    void reprocessOfUnsupportedRawStillFailsAndKeepsRaw() {
        assertThatThrownBy(() -> ingestionService.ingest(GarminFixtures.load(GarminFixtures.SWIMMING)))
                .isInstanceOf(GarminActivityMappingException.class);

        assertThatThrownBy(() -> ingestionService.reprocess("188090003"))
                .isInstanceOf(GarminActivityMappingException.class);

        assertThat(activityRepository.count()).isZero();
        assertThat(activityRawRepository.count()).isEqualTo(1);
    }

    @Test
    void reprocessUnknownGarminActivityFails() {
        assertThatThrownBy(() -> ingestionService.reprocess("does-not-exist"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("does-not-exist");
    }
}
