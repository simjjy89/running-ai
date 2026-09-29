package com.runningai.integration.garmin;

import com.fasterxml.jackson.databind.JsonNode;
import com.runningai.activity.Activity;
import com.runningai.activity.ActivityRawRepository;
import com.runningai.activity.ActivityRepository;
import com.runningai.activity.ExternalSource;
import com.runningai.athlete.AthleteService;
import com.runningai.integration.garmin.GarminConnectorException.Reason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static com.runningai.integration.garmin.GarminIncrementalSyncFixtures.activity;
import static com.runningai.integration.garmin.GarminIncrementalSyncFixtures.activityWithDistance;
import static com.runningai.integration.garmin.GarminIncrementalSyncFixtures.malformedNoStartTime;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * High-water mark + overlap window behaviour over the real ingestion core and H2
 * schema, with the connector replaced by a mock {@link GarminActivitySource}.
 * Uses default properties (page-size=50, overlap=7d, max-pages=10); the
 * max-pages-exhausted case needs a smaller page size and lives in
 * {@link GarminIncrementalSyncMaxPagesTest}.
 */
@SpringBootTest
@ActiveProfiles("test")
class GarminIncrementalSyncServiceTest {

    private static final DateTimeFormatter GARMIN_GMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneOffset.UTC);

    @Autowired
    private GarminIncrementalSyncService incrementalSyncService;

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

    private static List<JsonNode> page(int count, Instant newestStartTime) {
        List<JsonNode> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Instant startTime = newestStartTime.minus(Duration.ofHours(i));
            items.add(activity(9_000_000L + i, "running", GARMIN_GMT.format(startTime)));
        }
        return items;
    }

    @Test
    void bootstrapFetchesOnePageAndCreatesCheckpoint() {
        Instant newest = Instant.parse("2026-09-28T21:30:00Z");
        Instant oldest = Instant.parse("2026-09-27T10:05:30Z");
        when(activitySource.fetchActivities(eq(0), anyInt())).thenReturn(List.of(
                activity(1001, "running", "2026-09-28 21:30:00"),
                activity(1002, "treadmill_running", "2026-09-27 10:05:30")));

        GarminIncrementalSyncResult result = incrementalSyncService.syncIncremental();

        assertThat(result.fetched()).isEqualTo(2);
        assertThat(result.created()).isEqualTo(2);
        assertThat(result.updated()).isZero();
        assertThat(result.skipped()).isZero();
        assertThat(result.failed()).isZero();
        assertThat(result.pagesFetched()).isEqualTo(1);
        assertThat(result.checkpointAdvanced()).isTrue();
        assertThat(result.highWaterStartedAt()).isEqualTo(newest);
        verify(activitySource, org.mockito.Mockito.times(1)).fetchActivities(anyInt(), anyInt());

        GarminSyncState state = syncStateRepository.findByAthleteId(athleteId).orElseThrow();
        assertThat(state.getHighWaterStartedAt()).isEqualTo(newest);
        assertThat(state.getHighWaterStartedAt()).isAfter(oldest);
        assertThat(state.getLastSuccessfulSyncAt()).isNotNull();
    }

    @Test
    void secondIncrementalSyncAppliesCutoffAndDoesNotOverFetch() {
        Instant highWater = Instant.parse("2026-09-28T21:30:00Z");
        syncStateRepository.save(new GarminSyncState(athleteId, highWater, highWater));

        // default overlap = 7 days -> cutoff = 2026-09-21T21:30:00Z
        when(activitySource.fetchActivities(eq(0), anyInt())).thenReturn(List.of(
                activity(2001, "running", "2026-09-29 08:00:00"),   // newer than the current high-water
                activity(2002, "running", "2026-09-15 00:00:00")    // older than the cutoff
        ));

        GarminIncrementalSyncResult result = incrementalSyncService.syncIncremental();

        assertThat(result.pagesFetched()).isEqualTo(1);
        assertThat(result.checkpointAdvanced()).isTrue();
        assertThat(result.highWaterStartedAt()).isEqualTo(Instant.parse("2026-09-29T08:00:00Z"));
        verify(activitySource, org.mockito.Mockito.never()).fetchActivities(eq(50), anyInt());
    }

    @Test
    void lateGarminEditWithinOverlapIsCaptured() {
        when(activitySource.fetchActivities(eq(0), anyInt())).thenReturn(List.of(
                activity(3001, "running", "2026-09-26 00:00:00")));
        incrementalSyncService.syncIncremental();
        Activity before = activityRepository.findByExternalSourceAndExternalId(ExternalSource.GARMIN, "3001").orElseThrow();
        assertThat(before.getDistanceMeters()).isEqualTo(5000.0);

        // second sync: same activity, distance recomputed by Garmin, still inside the 7-day overlap
        reset(activitySource);
        when(activitySource.fetchActivities(eq(0), anyInt())).thenReturn(List.of(
                activityWithDistance(3001, "running", "2026-09-26 00:00:00", 5200.0)));

        GarminIncrementalSyncResult result = incrementalSyncService.syncIncremental();

        assertThat(result.created()).isZero();
        assertThat(result.updated()).isEqualTo(1);
        assertThat(activityRepository.count()).isEqualTo(1);
        Activity after = activityRepository.findByExternalSourceAndExternalId(ExternalSource.GARMIN, "3001").orElseThrow();
        assertThat(after.getId()).isEqualTo(before.getId());
        assertThat(after.getDistanceMeters()).isEqualTo(5200.0);
    }

    @Test
    void activitiesWithIdenticalStartTimeAreBothProcessed() {
        when(activitySource.fetchActivities(eq(0), anyInt())).thenReturn(List.of(
                activity(4001, "running", "2026-09-28 21:30:00"),
                activity(4002, "treadmill_running", "2026-09-28 21:30:00")));

        GarminIncrementalSyncResult result = incrementalSyncService.syncIncremental();

        assertThat(result.created()).isEqualTo(2);
        assertThat(activityRepository.count()).isEqualTo(2);
    }

    @Test
    void repeatIncrementalSyncOfSameWindowIsIdempotent() {
        when(activitySource.fetchActivities(eq(0), anyInt())).thenReturn(List.of(
                activity(5001, "running", "2026-09-28 21:30:00"),
                activity(5002, "treadmill_running", "2026-09-27 10:05:30")));
        incrementalSyncService.syncIncremental();
        List<Long> idsBefore = activityRepository.findAll().stream().map(Activity::getId).sorted().toList();

        GarminIncrementalSyncResult second = incrementalSyncService.syncIncremental();

        assertThat(second.created()).isZero();
        assertThat(second.updated()).isEqualTo(2);
        assertThat(activityRepository.count()).isEqualTo(2);
        assertThat(activityRawRepository.count()).isEqualTo(2);
        assertThat(activityRepository.findAll().stream().map(Activity::getId).sorted().toList()).isEqualTo(idsBefore);
    }

    @Test
    void unsupportedActivityIsSkippedButCheckpointStillAdvances() {
        when(activitySource.fetchActivities(eq(0), anyInt())).thenReturn(List.of(
                activity(6001, "running", "2026-09-28 21:30:00"),
                activity(6002, "lap_swimming", "2026-09-27 08:00:00")));

        GarminIncrementalSyncResult result = incrementalSyncService.syncIncremental();

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        assertThat(result.checkpointAdvanced()).isTrue();
        assertThat(activityRawRepository.count()).isEqualTo(2);
        assertThat(activityRepository.count()).isEqualTo(1);
    }

    @Test
    void malformedActivityPreventsCheckpointAdvance() {
        when(activitySource.fetchActivities(eq(0), anyInt())).thenReturn(List.of(
                activity(7001, "running", "2026-09-28 21:30:00"),
                malformedNoStartTime(7002)));

        GarminIncrementalSyncResult result = incrementalSyncService.syncIncremental();

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.checkpointAdvanced()).isFalse();
        assertThat(result.highWaterStartedAt()).isNull();
        assertThat(syncStateRepository.findByAthleteId(athleteId)).isEmpty();
        assertThat(activityRepository.count()).isEqualTo(1);   // the valid activity is still ingested and kept
    }

    @Test
    void connectorFailureDuringPaginationPreventsCheckpointAdvanceButKeepsIngestedActivities() {
        Instant highWater = Instant.parse("2026-09-28T21:30:00Z");
        syncStateRepository.save(new GarminSyncState(athleteId, highWater, highWater));

        // page 0: 50 items, all newer than cutoff (2026-09-21T21:30) -> pagination must continue
        when(activitySource.fetchActivities(eq(0), eq(50)))
                .thenReturn(page(50, Instant.parse("2026-09-29T00:00:00Z")));
        when(activitySource.fetchActivities(eq(50), eq(50)))
                .thenThrow(new GarminConnectorException(Reason.RATE_LIMITED, 429, "Garmin connector responded 429"));

        assertThatThrownBy(() -> incrementalSyncService.syncIncremental())
                .isInstanceOfSatisfying(GarminConnectorException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.RATE_LIMITED));

        assertThat(activityRepository.count()).isEqualTo(50);   // page 0 already committed
        assertThat(syncStateRepository.findByAthleteId(athleteId).orElseThrow().getHighWaterStartedAt())
                .isEqualTo(highWater);   // unchanged
    }
}
