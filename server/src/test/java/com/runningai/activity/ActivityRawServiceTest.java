package com.runningai.activity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runningai.athlete.AthleteService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Persistence tests for raw payload storage against the Flyway-managed schema.
 * flush + clear force a real round trip through the JSON column.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ActivityRawServiceTest {

    private static final Instant FIRST_FETCH = Instant.parse("2026-09-29T00:00:00Z");
    private static final Instant SECOND_FETCH = Instant.parse("2026-09-29T06:00:00Z");

    @Autowired
    private ActivityRawService activityRawService;

    @Autowired
    private ActivityRawRepository activityRawRepository;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private AthleteService athleteService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EntityManager entityManager;

    private JsonNode json(String content) throws Exception {
        return objectMapper.readTree(content);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void storesPayloadAsJsonAndReadsItBackUnchanged() throws Exception {
        JsonNode payload = json("""
                {"activityId": 188081596, "activityType": {"typeKey": "running"},
                 "distance": 10023.4, "splits": [{"km": 1, "sec": 312}, {"km": 2, "sec": 309}], "note": null}
                """);

        ActivityRaw saved = activityRawService.saveOrUpdate(ExternalSource.GARMIN, "188081596", payload, FIRST_FETCH);
        flushAndClear();

        ActivityRaw reloaded = activityRawRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getPayload()).isEqualTo(payload);
        assertThat(reloaded.getPayload().at("/activityType/typeKey").asText()).isEqualTo("running");
        assertThat(reloaded.getPayload().get("splits")).hasSize(2);
        assertThat(reloaded.getFetchedAt()).isEqualTo(FIRST_FETCH);
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getActivity()).isNull();
    }

    @Test
    void refetchReplacesPayloadAndFetchedAtInsteadOfInsertingAnotherRow() throws Exception {
        ActivityRaw first = activityRawService.saveOrUpdate(
                ExternalSource.GARMIN, "dup-raw", json("{\"v\": 1}"), FIRST_FETCH);
        flushAndClear();

        ActivityRaw second = activityRawService.saveOrUpdate(
                ExternalSource.GARMIN, "dup-raw", json("{\"v\": 2, \"extra\": true}"), SECOND_FETCH);
        flushAndClear();

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(activityRawRepository.count()).isEqualTo(1);

        ActivityRaw reloaded = activityRawService.find(ExternalSource.GARMIN, "dup-raw").orElseThrow();
        assertThat(reloaded.getPayload().get("v").asInt()).isEqualTo(2);
        assertThat(reloaded.getPayload().get("extra").asBoolean()).isTrue();
        assertThat(reloaded.getFetchedAt()).isEqualTo(SECOND_FETCH);
        // the database rounds to microseconds; the in-memory instant may carry nanos
        assertThat(reloaded.getCreatedAt().truncatedTo(ChronoUnit.MILLIS))
                .isEqualTo(first.getCreatedAt().truncatedTo(ChronoUnit.MILLIS));
    }

    @Test
    void linksToExistingActivityWithSameExternalIdentity() throws Exception {
        Long athleteId = athleteService.getDefaultAthlete().getId();
        Activity activity = activityRepository.save(new Activity(athleteId, ExternalSource.GARMIN, "linked-1",
                ActivityType.RUN, Instant.parse("2026-09-28T21:30:00Z"), 3600, 10000.0, 155, 172));
        flushAndClear();

        activityRawService.saveOrUpdate(ExternalSource.GARMIN, "linked-1", json("{\"ok\": true}"), FIRST_FETCH);
        flushAndClear();

        ActivityRaw reloaded = activityRawService.find(ExternalSource.GARMIN, "linked-1").orElseThrow();
        assertThat(reloaded.getActivity()).isNotNull();
        assertThat(reloaded.getActivity().getId()).isEqualTo(activity.getId());
    }

    @Test
    void sameExternalIdFromDifferentSourcesAreSeparateRows() throws Exception {
        activityRawService.saveOrUpdate(ExternalSource.GARMIN, "shared", json("{\"src\": \"garmin\"}"), FIRST_FETCH);
        activityRawService.saveOrUpdate(ExternalSource.INTERVALS_ICU, "shared", json("{\"src\": \"icu\"}"), FIRST_FETCH);
        flushAndClear();

        assertThat(activityRawRepository.count()).isEqualTo(2);
    }

    @Test
    void databaseRejectsDuplicateRawRowBypassingTheService() throws Exception {
        activityRawRepository.save(new ActivityRaw(ExternalSource.GARMIN, "constraint", json("{}"), FIRST_FETCH));
        flushAndClear();

        JsonNode again = json("{}");
        // IDENTITY ids make the insert run on save(), so the violation surfaces there or on flush.
        assertThatThrownBy(() -> {
            activityRawRepository.save(new ActivityRaw(ExternalSource.GARMIN, "constraint", again, SECOND_FETCH));
            entityManager.flush();
        }).isInstanceOfAny(DataIntegrityViolationException.class, jakarta.persistence.PersistenceException.class);
    }
}
