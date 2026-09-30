package com.runningai.training;

import com.runningai.activity.Activity;
import com.runningai.activity.ActivityRepository;
import com.runningai.activity.ActivityType;
import com.runningai.activity.ExternalSource;
import com.runningai.athlete.AthleteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP contract of GET /api/v1/workout-prescription; clock fixed at 2026-09-30 01:00 Asia/Seoul. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
@ActiveProfiles("test")
@Transactional
class WorkoutPrescriptionApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private AthleteService athleteService;

    @BeforeEach
    void setUp() {
        Long athleteId = athleteService.getDefaultAthlete().getId();
        // 2026-09-29 08:00 Seoul: 120 min long run
        activityRepository.save(new Activity(athleteId, ExternalSource.MANUAL, "wpa-1", ActivityType.RUN,
                Instant.parse("2026-09-28T23:00:00Z"), 7200, 18000.0, null, null));
    }

    @Test
    void defaultDateIsTheAthleteLocalTodayAndReturnsRecoveryStructure() throws Exception {
        // today (09-30 local) has no activity; the long run was yesterday -> RECOVERY
        mockMvc.perform(get("/api/v1/workout-prescription"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOfDate").value("2026-09-30"))
                .andExpect(jsonPath("$.intent").value("RECOVERY"))
                .andExpect(jsonPath("$.totalDurationMinutes").value(30))
                .andExpect(jsonPath("$.segments", hasSize(3)))
                .andExpect(jsonPath("$.segments[0].type").value("WARM_UP"))
                .andExpect(jsonPath("$.segments[0].durationMinutes").value(5))
                .andExpect(jsonPath("$.segments[0].intensityClass").value("VERY_EASY"))
                .andExpect(jsonPath("$.segments[0].description").value("Easy warm-up"))
                .andExpect(jsonPath("$.segments[1].type").value("MAIN"))
                .andExpect(jsonPath("$.segments[1].durationMinutes").value(20))
                .andExpect(jsonPath("$.segments[1].intensityClass").value("VERY_EASY"))
                .andExpect(jsonPath("$.segments[2].type").value("COOL_DOWN"))
                .andExpect(jsonPath("$.segments[2].durationMinutes").value(5))
                .andExpect(jsonPath("$.summary").value("30-minute recovery session with warm-up, steady main work, and cool-down."))
                .andExpect(jsonPath("$.recommendation.recommendedIntent").value("RECOVERY"))
                .andExpect(jsonPath("$.recommendation.durationMinMinutes").value(20))
                .andExpect(jsonPath("$.recommendation.durationMaxMinutes").value(40))
                .andExpect(jsonPath("$.recommendation.decisionContext.daysSinceLongRun").value(1));
    }

    @Test
    void explicitPastDateHasNoLookAhead() throws Exception {
        // as of 09-28 the long run is still in the future: empty history -> EASY 10/30/5
        mockMvc.perform(get("/api/v1/workout-prescription").param("date", "2026-09-28"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOfDate").value("2026-09-28"))
                .andExpect(jsonPath("$.intent").value("EASY"))
                .andExpect(jsonPath("$.totalDurationMinutes").value(45))
                .andExpect(jsonPath("$.segments[0].durationMinutes").value(10))
                .andExpect(jsonPath("$.segments[1].durationMinutes").value(30))
                .andExpect(jsonPath("$.segments[1].intensityClass").value("EASY"))
                .andExpect(jsonPath("$.segments[2].durationMinutes").value(5))
                .andExpect(jsonPath("$.recommendation.decisionContext.lastLongRunDate").doesNotExist());
    }

    @Test
    void responseHasNoTargetOrRenderingFields() throws Exception {
        for (String path : new String[]{"$", "$.segments[0]", "$.segments[1]", "$.segments[2]"}) {
            for (String field : new String[]{"pace", "paceMin", "paceMax", "speed", "heartRate", "heartRateTarget", "lthr",
                    "incline", "repeat", "repeatCount", "distanceTarget", "intervalDistance", "garminSteps", "steps"}) {
                mockMvc.perform(get("/api/v1/workout-prescription"))
                        .andExpect(jsonPath(path + "." + field).doesNotExist());
            }
        }
    }

    @Test
    void existingRecommendationEndpointIsUnchanged() throws Exception {
        mockMvc.perform(get("/api/v1/workout-recommendation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendedIntent").value("RECOVERY"))
                .andExpect(jsonPath("$.segments").doesNotExist());
    }

    @Test
    void malformedDateIsRejectedWithTheStructuredError() throws Exception {
        mockMvc.perform(get("/api/v1/workout-prescription").param("date", "2026-13-40"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.timestamp").exists());
    }
}
