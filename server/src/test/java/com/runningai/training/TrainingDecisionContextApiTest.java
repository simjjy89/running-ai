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

/** HTTP contract of GET /api/v1/training-decision-context; clock fixed at 2026-09-30 01:00 Asia/Seoul. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
@ActiveProfiles("test")
@Transactional
class TrainingDecisionContextApiTest {

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
        activityRepository.save(new Activity(athleteId, ExternalSource.MANUAL, "dca-1", ActivityType.RUN,
                Instant.parse("2026-09-28T23:00:00Z"), 7200, 18000.0, null, null));
        // 2026-09-30 00:30 Seoul (= 09-29T15:30Z): 30 min run; the UTC date is still 09-29
        activityRepository.save(new Activity(athleteId, ExternalSource.MANUAL, "dca-2", ActivityType.RUN,
                Instant.parse("2026-09-29T15:30:00Z"), 1800, 5000.0, null, null));
    }

    @Test
    void defaultDateIsTheAthleteLocalToday() throws Exception {
        mockMvc.perform(get("/api/v1/training-decision-context"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOfDate").value("2026-09-30"))
                .andExpect(jsonPath("$.trainingState.asOfDate").value("2026-09-30"))
                .andExpect(jsonPath("$.trainingState.acuteLoad").value(150.0))
                .andExpect(jsonPath("$.recentPattern", hasSize(14)))
                .andExpect(jsonPath("$.recentPattern[13].date").value("2026-09-30"))
                .andExpect(jsonPath("$.recentPattern[13].classification").value("EASY_OR_GENERAL"))
                .andExpect(jsonPath("$.recentPattern[13].classificationReason").value("RUNNING_ACTIVITY"))
                .andExpect(jsonPath("$.recentPattern[12].date").value("2026-09-29"))
                .andExpect(jsonPath("$.recentPattern[12].classification").value("LONG"))
                .andExpect(jsonPath("$.recentPattern[12].classificationReason").value("DURATION_THRESHOLD"))
                .andExpect(jsonPath("$.recentPattern[12].totalLoadMinutes").value(120.0))
                .andExpect(jsonPath("$.lastRunningDate").value("2026-09-30"))
                .andExpect(jsonPath("$.daysSinceRunning").value(0))
                .andExpect(jsonPath("$.lastActiveDate").value("2026-09-30"))
                .andExpect(jsonPath("$.daysSinceActive").value(0))
                .andExpect(jsonPath("$.lastLongRunDate").value("2026-09-29"))
                .andExpect(jsonPath("$.daysSinceLongRun").value(1))
                .andExpect(jsonPath("$.qualityDetectionAvailable").value(false))
                .andExpect(jsonPath("$.consecutiveActiveDays").value(2))
                .andExpect(jsonPath("$.consecutiveRestDays").value(0))
                .andExpect(jsonPath("$.loadTrend").value("UNKNOWN"))
                .andExpect(jsonPath("$.candidateTrainingTypes[0]").value("REST"))
                .andExpect(jsonPath("$.candidateTrainingTypes[1]").value("RECOVERY"))
                .andExpect(jsonPath("$.candidateTrainingTypes[2]").value("EASY"))
                .andExpect(jsonPath("$.candidateTrainingTypes", hasSize(3)))
                .andExpect(jsonPath("$.reasons[0]").value("LONG_RUN_RECENT"))
                .andExpect(jsonPath("$.reasons", hasSize(1)));
    }

    @Test
    void undefinedValuesAreExplicitNulls() throws Exception {
        mockMvc.perform(get("/api/v1/training-decision-context"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastQualityDate").value(nullValue()))
                .andExpect(jsonPath("$.daysSinceQuality").value(nullValue()))
                .andExpect(jsonPath("$.trainingState.weeklyLoadChangePercent").value(nullValue()));

        mockMvc.perform(get("/api/v1/training-decision-context").param("date", "2026-09-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastRunningDate").value(nullValue()))
                .andExpect(jsonPath("$.daysSinceRunning").value(nullValue()))
                .andExpect(jsonPath("$.lastActiveDate").value(nullValue()))
                .andExpect(jsonPath("$.lastLongRunDate").value(nullValue()))
                .andExpect(jsonPath("$.daysSinceLongRun").value(nullValue()))
                .andExpect(jsonPath("$.reasons", hasItem("LIMITED_HISTORY")));
    }

    @Test
    void explicitDateDoesNotSeeLaterActivities() throws Exception {
        // as of 09-28 both activities are in the future
        mockMvc.perform(get("/api/v1/training-decision-context").param("date", "2026-09-28"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOfDate").value("2026-09-28"))
                .andExpect(jsonPath("$.recentPattern[13].date").value("2026-09-28"))
                .andExpect(jsonPath("$.lastActiveDate").value(nullValue()))
                .andExpect(jsonPath("$.lastLongRunDate").value(nullValue()))
                .andExpect(jsonPath("$.trainingState.acuteLoad").value(0.0))
                .andExpect(jsonPath("$.candidateTrainingTypes", hasSize(3)))
                .andExpect(jsonPath("$.candidateTrainingTypes[0]").value("REST"))
                .andExpect(jsonPath("$.candidateTrainingTypes[1]").value("EASY"))
                .andExpect(jsonPath("$.candidateTrainingTypes[2]").value("CROSS_TRAINING"));
    }

    @Test
    void existingTrainingStateEndpointIsUnchanged() throws Exception {
        mockMvc.perform(get("/api/v1/training-state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOfDate").value("2026-09-30"))
                .andExpect(jsonPath("$.acuteLoad").value(150.0))
                .andExpect(jsonPath("$.recentPattern").doesNotExist());
    }

    @Test
    void malformedDateIsRejectedWithTheStructuredError() throws Exception {
        mockMvc.perform(get("/api/v1/training-decision-context").param("date", "2026-13-40"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.timestamp").exists());
    }
}
