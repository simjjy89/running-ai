package com.runningai.training;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract of GET /api/v1/workout-intensity-targets; clock fixed at 2026-09-30 01:00
 * Asia/Seoul (see {@link FixedClockTestConfig}). All LTHR/pace values below are synthetic.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
@ActiveProfiles("test")
@Transactional
class WorkoutIntensityTargetApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void noProfileGivesQualitativeFallbackWithOkStatus() throws Exception {
        mockMvc.perform(get("/api/v1/workout-intensity-targets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOfDate").value("2026-09-30"))
                .andExpect(jsonPath("$.intent").value("EASY"))
                .andExpect(jsonPath("$.targetAvailability").value("QUALITATIVE_ONLY"))
                .andExpect(jsonPath("$.profile.initialized").value(false))
                .andExpect(jsonPath("$.segments", hasSize(3)))
                .andExpect(jsonPath("$.segments[0].primaryTargetType").value("QUALITATIVE"))
                .andExpect(jsonPath("$.segments[0].paceTarget").doesNotExist())
                .andExpect(jsonPath("$.segments[0].heartRateTarget").doesNotExist())
                .andExpect(jsonPath("$.segments[0].treadmillTarget.minSpeedKph").doesNotExist())
                .andExpect(jsonPath("$.segments[0].treadmillTarget.minInclinePercent").exists())
                .andExpect(jsonPath("$.prescription.intent").value("EASY"));
    }

    @Test
    void fullProfileGivesPacePrimaryWithHeartRateAndTreadmillTargets() throws Exception {
        mockMvc.perform(put("/api/v1/athlete/intensity-profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lactateThresholdHeartRateBpm": 160, "lactateThresholdPaceSecondsPerKm": 300}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/workout-intensity-targets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetAvailability").value("FULL"))
                .andExpect(jsonPath("$.profile.initialized").value(true))
                .andExpect(jsonPath("$.segments[1].primaryTargetType").value("PACE"))
                .andExpect(jsonPath("$.segments[1].paceTarget.fastSecondsPerKm").exists())
                .andExpect(jsonPath("$.segments[1].heartRateTarget.minBpm").exists())
                .andExpect(jsonPath("$.segments[1].treadmillTarget.minSpeedKph").exists());
    }

    @Test
    void explicitPastDateHasNoLookAhead() throws Exception {
        mockMvc.perform(get("/api/v1/workout-intensity-targets").param("date", "2026-09-28"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOfDate").value("2026-09-28"))
                .andExpect(jsonPath("$.prescription.asOfDate").value("2026-09-28"));
    }

    @Test
    void malformedDateIsRejectedWithTheStructuredError() throws Exception {
        mockMvc.perform(get("/api/v1/workout-intensity-targets").param("date", "2026-13-40"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void noForbiddenRenderingOrIntervalFields() throws Exception {
        for (String field : new String[]{"repeatCount", "intervalDistance", "distanceTarget", "garminSteps", "steps",
                "repeat", "lthr"}) {
            mockMvc.perform(get("/api/v1/workout-intensity-targets"))
                    .andExpect(jsonPath("$.segments[0]." + field).doesNotExist());
        }
    }

    @Test
    void existingWorkoutPrescriptionEndpointIsUnchanged() throws Exception {
        mockMvc.perform(get("/api/v1/workout-prescription"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("EASY"))
                .andExpect(jsonPath("$.segments[0].paceTarget").doesNotExist())
                .andExpect(jsonPath("$.targetAvailability").doesNotExist());
    }
}
