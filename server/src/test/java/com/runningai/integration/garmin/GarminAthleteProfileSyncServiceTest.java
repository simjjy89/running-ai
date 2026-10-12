package com.runningai.integration.garmin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runningai.athlete.AthleteIntensityProfileResponse;
import com.runningai.athlete.AthleteIntensityProfileService;
import com.runningai.athlete.GarminProfileMergeResult;
import com.runningai.integration.garmin.GarminConnectorException.Reason;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Unit tests of the sync orchestration: source and profile service are both mocked. */
class GarminAthleteProfileSyncServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GarminLactateThresholdSource source = mock(GarminLactateThresholdSource.class);
    private final AthleteIntensityProfileService profileService = mock(AthleteIntensityProfileService.class);
    private final GarminAthleteProfileSyncService service = new GarminAthleteProfileSyncService(source, profileService, java.time.Clock.fixed(java.time.Instant.parse("2026-10-13T00:00:00Z"), java.time.ZoneOffset.UTC));

    private JsonNode json(String text) throws Exception {
        return objectMapper.readTree(text);
    }

    @Test
    void mapsTheRawSnapshotAndDelegatesToMerge() throws Exception {
        when(source.fetchLatest()).thenReturn(json("""
                {"speed_and_heart_rate": {"heartRate": 180, "speed": 0.34444348}}
                """));
        when(profileService.mergeGarminSnapshot(eq(180), eq(290), any(), any())).thenReturn(
                new GarminProfileMergeResult(true, true, true, new AthleteIntensityProfileResponse(true, 180, 290)));

        GarminProfileSyncResponse response = service.sync();

        assertThat(response.updated()).isTrue();
        assertThat(response.lactateThresholdHeartRateBpm()).isEqualTo(180);
        assertThat(response.lactateThresholdPaceSecondsPerKm()).isEqualTo(290);
        verify(profileService).mergeGarminSnapshot(eq(180), eq(290), any(), any());
    }

    @Test
    void unchangedMergeIsReflectedInTheResponse() throws Exception {
        when(source.fetchLatest()).thenReturn(json("""
                {"speed_and_heart_rate": {"heartRate": 180, "speed": 0.34444348}}
                """));
        when(profileService.mergeGarminSnapshot(eq(180), eq(290), any(), any())).thenReturn(
                new GarminProfileMergeResult(false, false, false, new AthleteIntensityProfileResponse(true, 180, 290)));

        GarminProfileSyncResponse response = service.sync();

        assertThat(response.updated()).isFalse();
    }

    @Test
    void bothMetricsMissingStillCallsMergeWithNulls() throws Exception {
        when(source.fetchLatest()).thenReturn(json("{\"speed_and_heart_rate\": {}}"));
        when(profileService.mergeGarminSnapshot(isNull(), isNull(), any(), any())).thenReturn(
                new GarminProfileMergeResult(false, false, false, AthleteIntensityProfileResponse.empty()));

        GarminProfileSyncResponse response = service.sync();

        assertThat(response.updated()).isFalse();
        verify(profileService).mergeGarminSnapshot(isNull(), isNull(), any(), any());
    }

    @Test
    void connectorFailurePropagatesAndNeverTouchesTheProfileService() {
        when(source.fetchLatest()).thenThrow(new GarminConnectorException(Reason.UNAVAILABLE, null, "not reachable"));

        assertThatThrownBy(service::sync).isInstanceOf(GarminConnectorException.class);

        verifyNoInteractions(profileService);
    }

    @Test
    void malformedSpeedStillAllowsTheHeartRateToMerge() throws Exception {
        when(source.fetchLatest()).thenReturn(json("""
                {"speed_and_heart_rate": {"heartRate": 180, "speed": "garbage"}}
                """));
        when(profileService.mergeGarminSnapshot(eq(180), isNull(), any(), any())).thenReturn(
                new GarminProfileMergeResult(true, true, false, new AthleteIntensityProfileResponse(true, 180, null)));

        service.sync();

        verify(profileService).mergeGarminSnapshot(eq(180), isNull(), any(), any());
    }
}
