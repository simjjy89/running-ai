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

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP contract of GET /api/v1/workout-recommendation; clock fixed at 2026-09-30 01:00 Asia/Seoul. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
@ActiveProfiles("test")
@Transactional
class WorkoutRecommendationApiTest {

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
        activityRepository.save(new Activity(athleteId, ExternalSource.MANUAL, "wra-1", ActivityType.RUN,
                Instant.parse("2026-09-28T23:00:00Z"), 7200, 18000.0, null, null));
        // 2026-09-30 00:30 Seoul (= 09-29T15:30Z): 30 min run; the UTC date is still 09-29
        activityRepository.save(new Activity(athleteId, ExternalSource.MANUAL, "wra-2", ActivityType.RUN,
                Instant.parse("2026-09-29T15:30:00Z"), 1800, 5000.0, null, null));
    }

    @Test
    void defaultDateIsTheAthleteLocalTodayAndReturnsRestWithNestedContext() throws Exception {
        mockMvc.perform(get("/api/v1/workout-recommendation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOfDate").value("2026-09-30"))
                .andExpect(jsonPath("$.recommendedIntent").value("REST"))
                .andExpect(jsonPath("$.durationMinMinutes").value(0))
                .andExpect(jsonPath("$.durationMaxMinutes").value(0))
                .andExpect(jsonPath("$.intensityClass").value("NONE"))
                .andExpect(jsonPath("$.confidence").value("MEDIUM"))
                .andExpect(jsonPath("$.dataSufficiency").value("LOW"))
                .andExpect(jsonPath("$.reasons[0]").value("RECENT_LONG_RUN"))
                .andExpect(jsonPath("$.summary").isNotEmpty())
                .andExpect(jsonPath("$.decisionContext.asOfDate").value("2026-09-30"))
                .andExpect(jsonPath("$.decisionContext.consecutiveActiveDays").value(2))
                .andExpect(jsonPath("$.decisionContext.daysSinceLongRun").value(1))
                .andExpect(jsonPath("$.decisionContext.recentPattern", hasSize(14)))
                .andExpect(jsonPath("$.decisionContext.candidateTrainingTypes", hasItem("REST")))
                .andExpect(jsonPath("$.decisionContext.trainingState.acuteLoad").value(150.0));
    }

    @Test
    void responseHasNoWorkoutStructureFields() throws Exception {
        mockMvc.perform(get("/api/v1/workout-recommendation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps").doesNotExist())
                .andExpect(jsonPath("$.warmup").doesNotExist())
                .andExpect(jsonPath("$.cooldown").doesNotExist())
                .andExpect(jsonPath("$.repeatCount").doesNotExist())
                .andExpect(jsonPath("$.paceMin").doesNotExist())
                .andExpect(jsonPath("$.paceMax").doesNotExist())
                .andExpect(jsonPath("$.heartRateTarget").doesNotExist())
                .andExpect(jsonPath("$.incline").doesNotExist())
                .andExpect(jsonPath("$.intervalDistance").doesNotExist());
    }

    @Test
    void explicitPastDateHasNoLookAhead() throws Exception {
        mockMvc.perform(get("/api/v1/workout-recommendation").param("date", "2026-09-28"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOfDate").value("2026-09-28"))
                .andExpect(jsonPath("$.recommendedIntent").value("EASY"))
                .andExpect(jsonPath("$.durationMinMinutes").value(30))
                .andExpect(jsonPath("$.durationMaxMinutes").value(60))
                .andExpect(jsonPath("$.intensityClass").value("EASY"))
                .andExpect(jsonPath("$.confidence").value("LOW"))
                .andExpect(jsonPath("$.dataSufficiency").value("LOW"))
                .andExpect(jsonPath("$.reasons", hasItem("LIMITED_HISTORY")))
                .andExpect(jsonPath("$.decisionContext.lastActiveDate").value(nullValue()))
                .andExpect(jsonPath("$.decisionContext.lastLongRunDate").value(nullValue()));
    }

    @Test
    void existingContextEndpointIsUnchanged() throws Exception {
        mockMvc.perform(get("/api/v1/training-decision-context"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendedIntent").doesNotExist())
                .andExpect(jsonPath("$.consecutiveActiveDays").value(2));
    }

    @Test
    void malformedDateIsRejectedWithTheStructuredError() throws Exception {
        mockMvc.perform(get("/api/v1/workout-recommendation").param("date", "2026-13-40"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.timestamp").exists());
    }
}
