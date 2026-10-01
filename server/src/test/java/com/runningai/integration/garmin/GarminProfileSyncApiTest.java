package com.runningai.integration.garmin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runningai.athlete.AthleteIntensityProfileRepository;
import com.runningai.athlete.AthleteIntensityProfileRequest;
import com.runningai.athlete.AthleteIntensityProfileService;
import com.runningai.integration.garmin.GarminConnectorException.Reason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Operational profile-sync API (Phase 6D) over the real merge logic + H2 schema; only the
 * connector ({@link GarminLactateThresholdSource}) is mocked. No network.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GarminProfileSyncApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AthleteIntensityProfileService profileService;

    @Autowired
    private AthleteIntensityProfileRepository profileRepository;

    @MockitoBean
    private GarminLactateThresholdSource lactateThresholdSource;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private JsonNode json(String text) throws Exception {
        return objectMapper.readTree(text);
    }

    @AfterEach
    void cleanUp() {
        profileRepository.deleteAll();
    }

    @Test
    void firstSyncInsertsBothMetrics() throws Exception {
        when(lactateThresholdSource.fetchLatest()).thenReturn(json("""
                {"speed_and_heart_rate": {"heartRate": 180, "speed": 0.34444348}}
                """));

        mockMvc.perform(post("/api/v1/garmin/profile-sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(true))
                .andExpect(jsonPath("$.lactateThresholdHeartRateBpm").value(180))
                .andExpect(jsonPath("$.lactateThresholdPaceSecondsPerKm").value(290));

        assertThat(profileService.getDefaultProfile().lactateThresholdHeartRateBpm()).isEqualTo(180);
    }

    @Test
    void repeatedSyncWithSameValueIsUnchanged() throws Exception {
        when(lactateThresholdSource.fetchLatest()).thenReturn(json("""
                {"speed_and_heart_rate": {"heartRate": 180, "speed": 0.34444348}}
                """));

        mockMvc.perform(post("/api/v1/garmin/profile-sync")).andExpect(jsonPath("$.updated").value(true));
        mockMvc.perform(post("/api/v1/garmin/profile-sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(false))
                .andExpect(jsonPath("$.lactateThresholdHeartRateBpm").value(180));
    }

    @Test
    void manualProfileIsPreservedWhenGarminHasNothingUsable() throws Exception {
        profileService.replaceDefaultProfile(new AthleteIntensityProfileRequest(180, 290));
        when(lactateThresholdSource.fetchLatest()).thenReturn(json("{\"speed_and_heart_rate\": {}}"));

        mockMvc.perform(post("/api/v1/garmin/profile-sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(false))
                .andExpect(jsonPath("$.lactateThresholdHeartRateBpm").value(180))
                .andExpect(jsonPath("$.lactateThresholdPaceSecondsPerKm").value(290));
    }

    @Test
    void connectorFailurePreservesTheExistingProfile() throws Exception {
        profileService.replaceDefaultProfile(new AthleteIntensityProfileRequest(180, 290));
        when(lactateThresholdSource.fetchLatest())
                .thenThrow(new GarminConnectorException(Reason.UNAVAILABLE, null, "not reachable"));

        mockMvc.perform(post("/api/v1/garmin/profile-sync")).andExpect(status().isServiceUnavailable());

        assertThat(profileService.getDefaultProfile().lactateThresholdHeartRateBpm()).isEqualTo(180);
        assertThat(profileService.getDefaultProfile().lactateThresholdPaceSecondsPerKm()).isEqualTo(290);
    }

    @ParameterizedTest
    @CsvSource({
            "AUTH_REQUIRED, 401, GARMIN_AUTH_REQUIRED",
            "FORBIDDEN, 403, GARMIN_FORBIDDEN",
            "RATE_LIMITED, 429, GARMIN_RATE_LIMITED",
            "UNAVAILABLE, 503, GARMIN_CONNECTOR_UNAVAILABLE",
            "UPSTREAM_ERROR, 502, GARMIN_UPSTREAM_ERROR"
    })
    void connectorFailuresMapToHttpErrors(Reason reason, int httpStatus, String code) throws Exception {
        when(lactateThresholdSource.fetchLatest()).thenThrow(new GarminConnectorException(reason, null, "connector says no"));

        mockMvc.perform(post("/api/v1/garmin/profile-sync"))
                .andExpect(status().is(httpStatus))
                .andExpect(jsonPath("$.code").value(code));
    }
}
