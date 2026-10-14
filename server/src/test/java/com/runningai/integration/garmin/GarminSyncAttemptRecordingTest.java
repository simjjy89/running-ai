package com.runningai.integration.garmin;

import com.fasterxml.jackson.databind.JsonNode;
import com.runningai.activity.ActivityRawRepository;
import com.runningai.activity.ActivityRepository;
import com.runningai.athlete.AthleteService;
import com.runningai.integration.garmin.GarminConnectorException.Reason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static com.runningai.integration.garmin.GarminIncrementalSyncFixtures.activity;
import static com.runningai.integration.garmin.GarminIncrementalSyncFixtures.malformedNoStartTime;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * Phase 8A: every Garmin incremental sync attempt that actually starts leaves a record, so the three
 * situations that used to look identical in the database - "Garmin had nothing new", "no sync has
 * run" and "the sync failed" - can be told apart afterwards.
 * <p>
 * The invariant these tests exist to protect is that recording is purely additive: it never advances
 * {@link GarminSyncState}, never swallows an exception and never changes what is ingested. The real
 * ingestion core and H2 schema are used; only the connector is mocked.
 */
@SpringBootTest
@ActiveProfiles("test")
class GarminSyncAttemptRecordingTest {

    @Autowired
    private GarminSyncOperationService operationService;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private ActivityRawRepository activityRawRepository;

    @Autowired
    private GarminSyncStateRepository syncStateRepository;

    @Autowired
    private GarminSyncAttemptRepository attemptRepository;

    @Autowired
    private AthleteService athleteService;

    @MockitoBean
    private GarminActivitySource activitySource;

    private Long athleteId;

    @BeforeEach
    void setUp() {
        athleteId = athleteService.getDefaultAthlete().getId();
    }

    @AfterEach
    void cleanUp() {
        activityRawRepository.deleteAll();
        activityRepository.deleteAll();
        syncStateRepository.deleteAll();
        attemptRepository.deleteAll();
    }

    private GarminSyncAttempt attempt() {
        return attemptRepository.findByAthleteId(athleteId).orElseThrow();
    }

    /** Scenario 1: one new running activity is collected. */
    @Test
    void aSuccessfulCollectionRecordsItsCountsAndAdvancesTheCheckpoint() {
        when(activitySource.fetchActivities(anyInt(), anyInt()))
                .thenReturn(List.of(activity(1001L, "running", "2026-10-10 06:00:00")));

        operationService.runSync();

        assertThat(activityRepository.findAll()).hasSize(1);
        GarminSyncAttempt attempt = attempt();
        assertThat(attempt.getOutcome()).isEqualTo(GarminSyncAttemptOutcome.SUCCESS);
        assertThat(attempt.getFetched()).isEqualTo(1);
        assertThat(attempt.getCreated()).isEqualTo(1);
        assertThat(attempt.getUpdated()).isZero();
        assertThat(attempt.getFailed()).isZero();
        assertThat(attempt.isCheckpointAdvanced()).isTrue();
        assertThat(attempt.getConsecutiveFailureCount()).isZero();
        assertThat(attempt.getFailureCode()).isNull();
        assertThat(attempt.getLastFailureAt()).isNull();
        assertThat(attempt.getAttemptedAt()).isNotNull();
    }

    /**
     * Scenario 2: the same activity collected twice. The second run must not create a second
     * activity, and the record must say so - "0 created, 1 updated" is the evidence that
     * de-duplication happened, rather than that nothing was fetched.
     */
    @Test
    void collectingTheSameActivityAgainRecordsAnUpdateNotACreate() {
        when(activitySource.fetchActivities(anyInt(), anyInt()))
                .thenReturn(List.of(activity(1001L, "running", "2026-10-10 06:00:00")));

        operationService.runSync();
        operationService.runSync();

        assertThat(activityRepository.findAll()).hasSize(1);
        assertThat(activityRawRepository.findAll()).hasSize(1);
        GarminSyncAttempt attempt = attempt();
        assertThat(attempt.getOutcome()).isEqualTo(GarminSyncAttemptOutcome.SUCCESS);
        assertThat(attempt.getCreated()).isZero();
        assertThat(attempt.getUpdated()).isEqualTo(1);
        // The high-water mark did not move: the newest start time is the one already recorded.
        assertThat(attempt.isCheckpointAdvanced()).isFalse();
        // One row per athlete, replaced each time - this is the latest outcome, not a history.
        assertThat(attemptRepository.findAll()).hasSize(1);
    }

    /**
     * Scenarios 3 and 4: Garmin answers 429, or authentication has expired. Both must be recorded
     * with their own code - a rate limit is waited out, an expired login needs a human on the
     * connector host - and neither may touch the checkpoint.
     */
    @ParameterizedTest
    @CsvSource({
            "RATE_LIMITED, 429, GARMIN_RATE_LIMITED",
            "AUTH_REQUIRED, 401, GARMIN_AUTH_REQUIRED",
            "FORBIDDEN, 403, GARMIN_FORBIDDEN",
            "UNAVAILABLE, , GARMIN_UNAVAILABLE",
    })
    void aConnectorFailureIsRecordedWithItsOwnCodeAndLeavesTheCheckpointAlone(Reason reason, Integer httpStatus,
                                                                             String expectedCode) {
        when(activitySource.fetchActivities(anyInt(), anyInt()))
                .thenThrow(new GarminConnectorException(reason, httpStatus, "connector failed"));

        assertThatThrownBy(() -> operationService.runSync()).isInstanceOf(GarminConnectorException.class);

        assertThat(syncStateRepository.findByAthleteId(athleteId)).isEmpty();
        GarminSyncAttempt attempt = attempt();
        assertThat(attempt.getOutcome()).isEqualTo(GarminSyncAttemptOutcome.FAILED);
        assertThat(attempt.getFailureCode()).isEqualTo(expectedCode);
        assertThat(attempt.getLastFailureCode()).isEqualTo(expectedCode);
        assertThat(attempt.getLastFailureAt()).isEqualTo(attempt.getAttemptedAt());
        assertThat(attempt.getFetched()).isZero();
        assertThat(attempt.getConsecutiveFailureCount()).isEqualTo(1);
    }

    /** A repeatedly failing scheduler is visible as a growing streak, not as a single stale failure. */
    @Test
    void repeatedFailuresAccumulateAStreakThatASuccessClearsWithoutErasingTheHistory() {
        when(activitySource.fetchActivities(anyInt(), anyInt()))
                .thenThrow(new GarminConnectorException(Reason.RATE_LIMITED, 429, "rate limited"));

        assertThatThrownBy(() -> operationService.runSync()).isInstanceOf(GarminConnectorException.class);
        assertThatThrownBy(() -> operationService.runSync()).isInstanceOf(GarminConnectorException.class);
        assertThat(attempt().getConsecutiveFailureCount()).isEqualTo(2);
        Instant failedAt = attempt().getLastFailureAt();

        doReturn(List.of(activity(1001L, "running", "2026-10-10 06:00:00")))
                .when(activitySource).fetchActivities(anyInt(), anyInt());
        operationService.runSync();

        GarminSyncAttempt attempt = attempt();
        assertThat(attempt.getOutcome()).isEqualTo(GarminSyncAttemptOutcome.SUCCESS);
        assertThat(attempt.getConsecutiveFailureCount()).isZero();
        assertThat(attempt.getFailureCode()).isNull();
        // The streak resets, but the fact that collection broke - and when - survives the success.
        assertThat(attempt.getLastFailureCode()).isEqualTo("GARMIN_RATE_LIMITED");
        assertThat(attempt.getLastFailureAt()).isEqualTo(failedAt);
    }

    /**
     * A run that completed but could not map every item is PARTIAL, not SUCCESS: the checkpoint is
     * deliberately held back in that case, and the record has to show why the next run re-covers the
     * same window rather than looking like an unexplained repeat.
     */
    @Test
    void anUnmappableItemIsRecordedAsPartialWithTheCheckpointHeldBack() {
        when(activitySource.fetchActivities(anyInt(), anyInt()))
                .thenReturn(List.of(activity(1001L, "running", "2026-10-10 06:00:00"), malformedNoStartTime(1002L)));

        operationService.runSync();

        assertThat(syncStateRepository.findByAthleteId(athleteId)).isEmpty();
        GarminSyncAttempt attempt = attempt();
        assertThat(attempt.getOutcome()).isEqualTo(GarminSyncAttemptOutcome.PARTIAL);
        assertThat(attempt.getFailed()).isEqualTo(1);
        assertThat(attempt.getCreated()).isEqualTo(1);
        assertThat(attempt.isCheckpointAdvanced()).isFalse();
        // A completed run is not a failed one, however partial: nothing to retry, only to finish.
        assertThat(attempt.getConsecutiveFailureCount()).isZero();
    }

    /**
     * Scenario 5: collection is interrupted part-way - here the connector dies between pages, which
     * is what an abrupt process exit looks like to the next run. Everything already ingested stays,
     * the checkpoint does not move, the failure is recorded, and the following run re-covers the same
     * ground and completes without duplicating anything.
     */
    @Test
    void anInterruptedRunKeepsWhatItIngestedAndTheNextRunCompletesItWithoutDuplicates() {
        // Bootstrap fetches a single page, and establishes the checkpoint the later runs page behind.
        doReturn(fullPage()).when(activitySource).fetchActivities(0, 50);
        operationService.runSync();
        assertThat(activityRepository.findAll()).hasSize(50);
        Instant checkpointBefore = syncStateRepository.findByAthleteId(athleteId).orElseThrow()
                .getHighWaterStartedAt();

        // The second, incremental run gets a full first page, so it asks for a second one - and the
        // connector is gone by then.
        doThrow(new GarminConnectorException(Reason.UNAVAILABLE, null, "connector died"))
                .when(activitySource).fetchActivities(50, 50);
        assertThatThrownBy(() -> operationService.runSync()).isInstanceOf(GarminConnectorException.class);

        assertThat(activityRepository.findAll()).hasSize(50);
        assertThat(syncStateRepository.findByAthleteId(athleteId).orElseThrow().getHighWaterStartedAt())
                .isEqualTo(checkpointBefore);
        assertThat(attempt().getOutcome()).isEqualTo(GarminSyncAttemptOutcome.FAILED);
        assertThat(attempt().getFailureCode()).isEqualTo("GARMIN_UNAVAILABLE");

        // The connector comes back: the same window is re-covered and nothing is duplicated.
        doReturn(List.<JsonNode>of()).when(activitySource).fetchActivities(50, 50);
        operationService.runSync();

        assertThat(activityRepository.findAll()).hasSize(50);
        assertThat(activityRawRepository.findAll()).hasSize(50);
        GarminSyncAttempt attempt = attempt();
        assertThat(attempt.getOutcome()).isEqualTo(GarminSyncAttemptOutcome.SUCCESS);
        assertThat(attempt.getCreated()).isZero();
        assertThat(attempt.getUpdated()).isEqualTo(50);
        assertThat(attempt.getConsecutiveFailureCount()).isZero();
    }

    /**
     * Scenario 6: two syncs at once. The rejected one never reached Garmin, so it is not a collection
     * attempt and must not appear in the record - otherwise a busy scheduler would manufacture
     * failures that never happened.
     */
    @Test
    void aRejectedConcurrentRunIsNotRecordedAsAnAttempt() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(activitySource.fetchActivities(anyInt(), anyInt())).thenAnswer(invocation -> {
            inside.countDown();
            release.await(5, TimeUnit.SECONDS);
            return List.of(activity(1001L, "running", "2026-10-10 06:00:00"));
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<GarminSyncResponse> first = executor.submit(operationService::runSync);
            assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> operationService.runSync())
                    .isInstanceOf(GarminSyncAlreadyRunningException.class);
            assertThat(attemptRepository.findAll()).isEmpty();

            release.countDown();
            first.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        // Only the run that really happened is recorded, and it is recorded as a success.
        assertThat(attemptRepository.findAll()).hasSize(1);
        assertThat(attempt().getOutcome()).isEqualTo(GarminSyncAttemptOutcome.SUCCESS);
    }

    /**
     * Exactly one full page (page-size 50) of activities close enough together that the whole page
     * stays inside the overlap window, so an incremental run must ask for a second page.
     */
    private static List<JsonNode> fullPage() {
        return IntStream.range(0, 50)
                .mapToObj(i -> activity(2000L + i, "running", String.format("2026-10-10 06:%02d:00", i)))
                .toList();
    }
}
