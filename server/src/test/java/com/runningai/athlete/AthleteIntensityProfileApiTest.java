package com.runningai.athlete;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP contract of GET/PUT /api/v1/athlete/intensity-profile. All values below are synthetic. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AthleteIntensityProfileApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void getBeforeAnyPutReturnsUninitializedNotFourOhFour() throws Exception {
        mockMvc.perform(get("/api/v1/athlete/intensity-profile"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"initialized": false, "lactateThresholdHeartRateBpm": null, "lactateThresholdPaceSecondsPerKm": null}
                        """, true));
    }

    @Test
    void putThenGetRoundTrips() throws Exception {
        mockMvc.perform(put("/api/v1/athlete/intensity-profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lactateThresholdHeartRateBpm": 170, "lactateThresholdPaceSecondsPerKm": 300}
                                """))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"initialized": true, "lactateThresholdHeartRateBpm": 170, "lactateThresholdPaceSecondsPerKm": 300}
                        """, true));

        mockMvc.perform(get("/api/v1/athlete/intensity-profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.initialized").value(true))
                .andExpect(jsonPath("$.lactateThresholdHeartRateBpm").value(170))
                .andExpect(jsonPath("$.lactateThresholdPaceSecondsPerKm").value(300));
    }

    @Test
    void nonPositiveLthrIsRejectedWithFourHundred() throws Exception {
        mockMvc.perform(put("/api/v1/athlete/intensity-profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lactateThresholdHeartRateBpm": 0, "lactateThresholdPaceSecondsPerKm": 300}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[?(@.field == 'lactateThresholdHeartRateBpm')]").exists());
    }

    @Test
    void nonPositiveThresholdPaceIsRejectedWithFourHundred() throws Exception {
        mockMvc.perform(put("/api/v1/athlete/intensity-profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lactateThresholdHeartRateBpm": 170, "lactateThresholdPaceSecondsPerKm": -5}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[?(@.field == 'lactateThresholdPaceSecondsPerKm')]").exists());
    }

    @Test
    void partialProfileIsAccepted() throws Exception {
        mockMvc.perform(put("/api/v1/athlete/intensity-profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lactateThresholdHeartRateBpm": null, "lactateThresholdPaceSecondsPerKm": 300}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.initialized").value(true))
                .andExpect(jsonPath("$.lactateThresholdHeartRateBpm").doesNotExist())
                .andExpect(jsonPath("$.lactateThresholdPaceSecondsPerKm").value(300));
    }
}
