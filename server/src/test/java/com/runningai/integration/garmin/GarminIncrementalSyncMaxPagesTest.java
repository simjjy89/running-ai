package com.runningai.integration.garmin;

import com.runningai.activity.ActivityRawRepository;
import com.runningai.activity.ActivityRepository;
import com.runningai.athlete.AthleteService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;

import static com.runningai.integration.garmin.GarminIncrementalSyncFixtures.activity;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A small page-size/max-pages configuration, isolated in its own Spring context
 * so the rest of the incremental sync tests can keep the real (much larger)
 * defaults. See {@link GarminIncrementalSyncServiceTest} for the main behaviour.
 */
@SpringBootTest(properties = {
        "running-ai.garmin.sync.page-size=1",
        "running-ai.garmin.sync.max-pages=2"
})
@ActiveProfiles("test")
class GarminIncrementalSyncMaxPagesTest {

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

    @Test
    void maxPagesExhaustedBeforeCutoffDoesNotAdvanceCheckpoint() {
        Instant highWater = Instant.parse("2026-09-28T21:30:00Z");
        syncStateRepository.save(new GarminSyncState(athleteId, highWater, highWater));

        // cutoff = highWater - 7d = 2026-09-21T21:30:00Z; both single-item pages stay well above it
        when(activitySource.fetchActivities(eq(0), eq(1))).thenReturn(List.of(
                activity(9001, "running", "2026-09-29 00:00:00")));
        when(activitySource.fetchActivities(eq(1), eq(1))).thenReturn(List.of(
                activity(9002, "running", "2026-09-28 23:00:00")));

        assertThatThrownBy(() -> incrementalSyncService.syncIncremental())
                .isInstanceOf(GarminIncrementalSyncException.class);

        assertThat(activityRepository.count()).isEqualTo(2);   // both pages were ingested
        assertThat(syncStateRepository.findByAthleteId(athleteId).orElseThrow().getHighWaterStartedAt())
                .isEqualTo(highWater);   // unchanged: the window was never proven complete
        verify(activitySource, never()).fetchActivities(eq(2), anyInt());
    }
}
