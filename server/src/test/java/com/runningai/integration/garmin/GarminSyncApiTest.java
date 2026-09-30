package com.runningai.integration.garmin;

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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.runningai.integration.garmin.GarminIncrementalSyncFixtures.activity;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Operational sync API over the real incremental sync + H2 schema; only the connector
 * ({@link GarminActivitySource}) is mocked. No network.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GarminSyncApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private ActivityRawRepository activityRawRepository;

    @Autowired
    private GarminSyncStateRepository syncStateRepository;

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
    }

    @Test
    void statusIsUninitializedWithoutCheckpoint() throws Exception {
        mockMvc.perform(get("/api/v1/garmin/sync/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.initialized").value(false))
                .andExpect(jsonPath("$.highWaterStartedAt").value((Object) null))
                .andExpect(jsonPath("$.lastSuccessfulSyncAt").value((Object) null));
    }

    @Test
    void statusReadsCheckpointWithoutCallingTheConnector() throws Exception {
        syncStateRepository.save(new GarminSyncState(athleteId,
                Instant.parse("2026-09-29T02:10:08Z"), Instant.parse("2026-09-30T00:30:12Z")));

        mockMvc.perform(get("/api/v1/garmin/sync/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.initialized").value(true))
                .andExpect(jsonPath("$.highWaterStartedAt").value("2026-09-29T02:10:08Z"))
                .andExpect(jsonPath("$.lastSuccessfulSyncAt").value("2026-09-30T00:30:12Z"));

        org.mockito.Mockito.verifyNoInteractions(activitySource);
    }

    @Test
    void syncReturnsResultAndCreatesCheckpoint() throws Exception {
        when(activitySource.fetchActivities(anyInt(), anyInt())).thenReturn(List.of(
                activity(4001, "running", "2026-09-28 21:30:00"),
                activity(4002, "lap_swimming", "2026-09-27 08:00:00")));

        mockMvc.perform(post("/api/v1/garmin/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fetched").value(2))
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.updated").value(0))
                .andExpect(jsonPath("$.skipped").value(1))
                .andExpect(jsonPath("$.failed").value(0))
                .andExpect(jsonPath("$.pagesFetched").value(1))
                .andExpect(jsonPath("$.checkpointAdvanced").value(true))
                .andExpect(jsonPath("$.highWaterStartedAt").value("2026-09-28T21:30:00Z"))
                .andExpect(jsonPath("$.lastSuccessfulSyncAt").isNotEmpty());

        mockMvc.perform(get("/api/v1/garmin/sync/status"))
                .andExpect(jsonPath("$.initialized").value(true))
                .andExpect(jsonPath("$.highWaterStartedAt").value("2026-09-28T21:30:00Z"));
    }

    @Test
    void syncWithNoNewActivityKeepsHighWaterButRefreshesLastSuccessfulSync() throws Exception {
        Instant highWater = Instant.parse("2026-09-28T21:30:00Z");
        Instant oldSync = Instant.parse("2026-09-01T00:00:00Z");
        syncStateRepository.save(new GarminSyncState(athleteId, highWater, oldSync));
        // only overlap data: the newest item equals the current high-water mark
        when(activitySource.fetchActivities(anyInt(), anyInt())).thenReturn(List.of(
                activity(5001, "running", "2026-09-28 21:30:00"),
                activity(5002, "running", "2026-09-20 08:00:00")));

        mockMvc.perform(post("/api/v1/garmin/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkpointAdvanced").value(false))
                .andExpect(jsonPath("$.highWaterStartedAt").value("2026-09-28T21:30:00Z"));

        GarminSyncState state = syncStateRepository.findByAthleteId(athleteId).orElseThrow();
        assertThat(state.getHighWaterStartedAt()).isEqualTo(highWater);
        assertThat(state.getLastSuccessfulSyncAt()).isAfter(oldSync);
    }

    @Test
    void failedSyncDoesNotMoveTheCheckpoint() throws Exception {
        when(activitySource.fetchActivities(anyInt(), anyInt()))
                .thenThrow(new GarminConnectorException(Reason.UNAVAILABLE, null, "not reachable"));

        mockMvc.perform(post("/api/v1/garmin/sync")).andExpect(status().isServiceUnavailable());

        assertThat(syncStateRepository.findByAthleteId(athleteId)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "AUTH_REQUIRED, 401, GARMIN_AUTH_REQUIRED",
            "FORBIDDEN, 403, GARMIN_FORBIDDEN",
            "RATE_LIMITED, 429, GARMIN_RATE_LIMITED",
            "UNAVAILABLE, 503, GARMIN_CONNECTOR_UNAVAILABLE",
            "UPSTREAM_ERROR, 502, GARMIN_UPSTREAM_ERROR",
            "CONNECTOR_ERROR, 502, GARMIN_UPSTREAM_ERROR",
            "INVALID_RESPONSE, 502, GARMIN_UPSTREAM_ERROR"
    })
    void connectorFailuresMapToHttpErrors(Reason reason, int httpStatus, String code) throws Exception {
        when(activitySource.fetchActivities(anyInt(), anyInt()))
                .thenThrow(new GarminConnectorException(reason, null, "connector says no"));

        mockMvc.perform(post("/api/v1/garmin/sync"))
                .andExpect(status().is(httpStatus))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void incompleteWindowMapsTo503() throws Exception {
        // every page is full and older than nothing: with a checkpoint whose cutoff is never reached
        Instant highWater = Instant.parse("2026-09-28T21:30:00Z");
        syncStateRepository.save(new GarminSyncState(athleteId, highWater, highWater));
        List<com.fasterxml.jackson.databind.JsonNode> fullPage = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            fullPage.add(activity(6000L + i, "running", "2026-09-28 21:30:00"));
        }
        when(activitySource.fetchActivities(anyInt(), anyInt())).thenReturn(fullPage);

        mockMvc.perform(post("/api/v1/garmin/sync"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("INCREMENTAL_WINDOW_INCOMPLETE"));
    }

    @Test
    void concurrentSyncIsRejectedWith409AndLockIsReleasedAfterwards() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(activitySource.fetchActivities(anyInt(), anyInt())).thenAnswer(inv -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test did not release the blocked sync");
            }
            return List.of(activity(7001, "running", "2026-09-28 21:30:00"));
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> first = executor.submit(() ->
                    mockMvc.perform(post("/api/v1/garmin/sync")).andReturn().getResponse().getStatus());
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

            mockMvc.perform(post("/api/v1/garmin/sync"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("GARMIN_SYNC_ALREADY_RUNNING"))
                    .andExpect(jsonPath("$.message").value("Garmin sync is already running"))
                    .andExpect(jsonPath("$.timestamp").exists());

            release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }

        // lock released: another sync runs normally
        doReturn(List.of()).when(activitySource).fetchActivities(anyInt(), anyInt());
        mockMvc.perform(post("/api/v1/garmin/sync")).andExpect(status().isOk());
    }

    @Test
    void lockIsReleasedAfterAFailedSync() throws Exception {
        when(activitySource.fetchActivities(anyInt(), anyInt()))
                .thenThrow(new GarminConnectorException(Reason.RATE_LIMITED, 429, "rate limited"));
        mockMvc.perform(post("/api/v1/garmin/sync")).andExpect(status().isTooManyRequests());

        doReturn(List.of(activity(8001, "running", "2026-09-28 21:30:00")))
                .when(activitySource).fetchActivities(anyInt(), anyInt());
        mockMvc.perform(post("/api/v1/garmin/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1));
    }

    @Test
    void lockIsReleasedAfterAnUnexpectedException() throws Exception {
        when(activitySource.fetchActivities(anyInt(), anyInt())).thenThrow(new IllegalStateException("boom"));
        mockMvc.perform(post("/api/v1/garmin/sync")).andExpect(status().isInternalServerError());

        doReturn(List.of()).when(activitySource).fetchActivities(anyInt(), anyInt());
        mockMvc.perform(post("/api/v1/garmin/sync")).andExpect(status().isOk());
    }
}
