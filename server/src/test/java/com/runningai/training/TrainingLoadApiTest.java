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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP contract of the training load endpoints; the clock is fixed at 2026-09-30 01:00 Asia/Seoul. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
@ActiveProfiles("test")
@Transactional
class TrainingLoadApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private AthleteService athleteService;

    @BeforeEach
    void setUp() {
        Long athleteId = athleteService.getDefaultAthlete().getId();
        // 2026-09-30 00:30 Seoul (= 09-29T15:30Z): 60 min run, 10 km
        activityRepository.save(new Activity(athleteId, ExternalSource.MANUAL, "api-1", ActivityType.RUN,
                Instant.parse("2026-09-29T15:30:00Z"), 3600, 10000.0, null, null));
        // 2026-09-28 Monday 07:00 Seoul: 45 min indoor cycling
        activityRepository.save(new Activity(athleteId, ExternalSource.MANUAL, "api-2", ActivityType.INDOOR_CYCLING,
                Instant.parse("2026-09-27T22:00:00Z"), 2700, null, null, null));
    }

    @Test
    void defaultDateIsTheAthleteLocalToday() throws Exception {
        mockMvc.perform(get("/api/v1/training-load"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOfDate").value("2026-09-30"))
                .andExpect(jsonPath("$.load7Days").value(105.0))
                .andExpect(jsonPath("$.load28Days").value(105.0))
                .andExpect(jsonPath("$.runningDistance7DaysMeters").value(10000.0))
                .andExpect(jsonPath("$.runningDistance28DaysMeters").value(10000.0))
                .andExpect(jsonPath("$.runningDuration7DaysSeconds").value(3600))
                .andExpect(jsonPath("$.runningDuration28DaysSeconds").value(3600))
                .andExpect(jsonPath("$.activityCount7Days").value(2))
                .andExpect(jsonPath("$.activityCount28Days").value(2));
    }

    @Test
    void explicitDateChangesTheWindow() throws Exception {
        // as of 2026-09-29 the 09-30 run is in the future (excluded); the Monday ride is included
        mockMvc.perform(get("/api/v1/training-load").param("date", "2026-09-29"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOfDate").value("2026-09-29"))
                .andExpect(jsonPath("$.load7Days").value(45.0))
                .andExpect(jsonPath("$.activityCount7Days").value(1))
                .andExpect(jsonPath("$.runningDistance7DaysMeters").value(0.0));
    }

    @Test
    void weeklyDefaultsToTheCurrentLocalWeek() throws Exception {
        mockMvc.perform(get("/api/v1/training-load/weekly"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weekStart").value("2026-09-28"))
                .andExpect(jsonPath("$.weekEnd").value("2026-10-04"))
                .andExpect(jsonPath("$.activityCount").value(2))
                .andExpect(jsonPath("$.trainingLoadMinutes").value(105.0))
                .andExpect(jsonPath("$.runningDistanceMeters").value(10000.0))
                .andExpect(jsonPath("$.runningDurationSeconds").value(3600))
                .andExpect(jsonPath("$.cyclingDurationSeconds").value(2700));
    }

    @Test
    void weeklyWithExplicitDateReturnsThatIsoWeek() throws Exception {
        mockMvc.perform(get("/api/v1/training-load/weekly").param("date", "2026-09-27"))   // Sunday of the previous week
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weekStart").value("2026-09-21"))
                .andExpect(jsonPath("$.weekEnd").value("2026-09-27"))
                .andExpect(jsonPath("$.activityCount").value(0));
    }

    @Test
    void invalidDateIsRejectedWithTheStructuredError() throws Exception {
        mockMvc.perform(get("/api/v1/training-load").param("date", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.timestamp").exists());
        mockMvc.perform(get("/api/v1/training-load/weekly").param("date", "2026-13-40"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
