package com.runningai.integration.garmin;

import com.fasterxml.jackson.databind.JsonNode;
import com.runningai.activity.Activity;
import com.runningai.activity.ActivityRawRepository;
import com.runningai.activity.ActivityRepository;
import com.runningai.activity.ExternalSource;
import com.runningai.integration.garmin.GarminConnectorException.Reason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * Sync behaviour over the real ingestion core and H2 schema, with the connector
 * replaced by a mock {@link GarminActivitySource}. Not transactional (raw-first
 * commits are part of what is verified); rows are cleaned up after each test.
 */
@SpringBootTest
@ActiveProfiles("test")
class GarminSyncServiceTest {

    @Autowired
    private GarminSyncService syncService;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private ActivityRawRepository activityRawRepository;

    @MockitoBean
    private GarminActivitySource activitySource;

    @AfterEach
    void cleanUp() {
        activityRawRepository.deleteAll();
        activityRepository.deleteAll();
    }

    private static List<JsonNode> supportedThree() {
        return List.of(
                GarminFixtures.load(GarminFixtures.RUNNING),
                GarminFixtures.load(GarminFixtures.TREADMILL),
                GarminFixtures.load(GarminFixtures.INDOOR_CYCLING));
    }

    @Test
    void firstSyncCreatesAllSupportedActivities() {
        when(activitySource.fetchRecentActivities(anyInt())).thenReturn(supportedThree());

        GarminSyncResult result = syncService.syncRecent(20);

        assertThat(result).isEqualTo(new GarminSyncResult(3, 3, 0, 0, 0));
        assertThat(activityRepository.count()).isEqualTo(3);
        assertThat(activityRawRepository.count()).isEqualTo(3);
    }

    @Test
    void secondSyncOfTheSameWindowIsIdempotent() {
        when(activitySource.fetchRecentActivities(anyInt())).thenReturn(supportedThree());
        syncService.syncRecent(20);
        List<Long> idsBefore = activityRepository.findAll().stream().map(Activity::getId).sorted().toList();

        GarminSyncResult second = syncService.syncRecent(20);

        assertThat(second).isEqualTo(new GarminSyncResult(3, 0, 3, 0, 0));
        assertThat(activityRepository.count()).isEqualTo(3);
        assertThat(activityRawRepository.count()).isEqualTo(3);
        assertThat(activityRepository.findAll().stream().map(Activity::getId).sorted().toList()).isEqualTo(idsBefore);
    }

    @Test
    void unsupportedActivityIsSkippedButItsRawIsKept() {
        when(activitySource.fetchRecentActivities(anyInt())).thenReturn(List.of(
                GarminFixtures.load(GarminFixtures.RUNNING),
                GarminFixtures.load(GarminFixtures.SWIMMING)));

        GarminSyncResult result = syncService.syncRecent(20);

        assertThat(result).isEqualTo(new GarminSyncResult(2, 1, 0, 1, 0));
        assertThat(activityRepository.count()).isEqualTo(1);
        assertThat(activityRawRepository.count()).isEqualTo(2);
        assertThat(activityRawRepository.findByExternalSourceAndExternalId(ExternalSource.GARMIN, "188090003")).isPresent();
    }

    @Test
    void malformedItemsAreCountedAsFailedAndDoNotStopTheSync() {
        when(activitySource.fetchRecentActivities(anyInt())).thenReturn(List.of(
                GarminFixtures.load(GarminFixtures.MISSING_ACTIVITY_ID),   // no id: nothing stored
                GarminFixtures.load(GarminFixtures.MISSING_START_TIME),    // id but unmappable: raw kept
                GarminFixtures.load(GarminFixtures.RUNNING)));

        GarminSyncResult result = syncService.syncRecent(20);

        assertThat(result).isEqualTo(new GarminSyncResult(3, 1, 0, 0, 2));
        assertThat(activityRepository.count()).isEqualTo(1);
        assertThat(activityRawRepository.count()).isEqualTo(2);
    }

    @Test
    void connectorFailureAbortsTheSyncWithoutIngestingAnything() {
        when(activitySource.fetchRecentActivities(anyInt()))
                .thenThrow(new GarminConnectorException(Reason.RATE_LIMITED, 429, "Garmin connector responded 429"));

        assertThatThrownBy(() -> syncService.syncRecent(20))
                .isInstanceOfSatisfying(GarminConnectorException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.RATE_LIMITED));

        assertThat(activityRepository.count()).isZero();
        assertThat(activityRawRepository.count()).isZero();
    }

    @Test
    void emptyWindowIsASuccessfulNoOp() {
        when(activitySource.fetchRecentActivities(anyInt())).thenReturn(List.of());

        assertThat(syncService.syncRecent(5)).isEqualTo(new GarminSyncResult(0, 0, 0, 0, 0));
    }
}
