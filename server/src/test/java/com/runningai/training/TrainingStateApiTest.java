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

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP contract of GET /api/v1/training-state; clock fixed at 2026-09-30 01:00 Asia/Seoul. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
@ActiveProfiles("test")
@Transactional
class TrainingStateApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private AthleteService athleteService;

    @BeforeEach
    void setUp() {
        Long athleteId = athleteService.getDefaultAthlete().getId();
        // previous 7 days: 2026-09-20 06:00 Seoul, 100 min, 10 km
        activityRepository.save(new Activity(athleteId, ExternalSource.MANUAL, "st-1", ActivityType.RUN,
                Instant.parse("2026-09-19T21:00:00Z"), 6000, 10000.0, null, null));
        // current 7 days: 2026-09-30 00:30 Seoul (= 09-29T15:30Z), 120 min, 15 km
        activityRepository.save(new Activity(athleteId, ExternalSource.MANUAL, "st-2", ActivityType.RUN,
                Instant.parse("2026-09-29T15:30:00Z"), 7200, 15000.0, null, null));
    }

    @Test
    void defaultDateIsTheAthleteLocalToday() throws Exception {
        mockMvc.perform(get("/api/v1/training-state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOfDate").value("2026-09-30"))
                .andExpect(jsonPath("$.acuteLoad").value(120.0))
                .andExpect(jsonPath("$.chronicLoad").value(55.0))
                .andExpect(jsonPath("$.acuteChronicRatio").value(closeTo(120.0 / 55.0, 1e-9)))
                .andExpect(jsonPath("$.current7DayLoad").value(120.0))
                .andExpect(jsonPath("$.previous7DayLoad").value(100.0))
                .andExpect(jsonPath("$.weeklyLoadChangePercent").value(closeTo(20.0, 1e-9)))
                .andExpect(jsonPath("$.runningDistance7DaysMeters").value(15000.0))
                .andExpect(jsonPath("$.previousRunningDistance7DaysMeters").value(10000.0))
                .andExpect(jsonPath("$.runningDistanceChangePercent").value(closeTo(50.0, 1e-9)))
                .andExpect(jsonPath("$.runningDuration7DaysSeconds").value(7200))
                .andExpect(jsonPath("$.previousRunningDuration7DaysSeconds").value(6000))
                .andExpect(jsonPath("$.runningDurationChangePercent").value(closeTo(20.0, 1e-9)))
                .andExpect(jsonPath("$.rampLoad").value(20.0))
                .andExpect(jsonPath("$.activeDays7Days").value(1))
                .andExpect(jsonPath("$.restDays7Days").value(6));
    }

    @Test
    void undefinedValuesAreExplicitNulls() throws Exception {
        // one active day in the current week: SD > 0 so monotony exists; use an earlier date with nothing to get nulls
        mockMvc.perform(get("/api/v1/training-state").param("date", "2026-09-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOfDate").value("2026-09-10"))
                .andExpect(jsonPath("$.acuteLoad").value(0.0))
                .andExpect(jsonPath("$.acuteChronicRatio").value(nullValue()))
                .andExpect(jsonPath("$.weeklyLoadChangePercent").value(nullValue()))
                .andExpect(jsonPath("$.runningDistanceChangePercent").value(nullValue()))
                .andExpect(jsonPath("$.runningDurationChangePercent").value(nullValue()))
                .andExpect(jsonPath("$.monotony").value(nullValue()))
                .andExpect(jsonPath("$.strain").value(nullValue()))
                .andExpect(jsonPath("$.activeDays7Days").value(0))
                .andExpect(jsonPath("$.restDays7Days").value(7));
    }

    @Test
    void explicitDateComputesThatDay() throws Exception {
        // as of 2026-09-26 the current week is 09-20..09-26 (contains the 100-minute run), the 09-30 run is in the future
        mockMvc.perform(get("/api/v1/training-state").param("date", "2026-09-26"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOfDate").value("2026-09-26"))
                .andExpect(jsonPath("$.current7DayLoad").value(100.0))
                .andExpect(jsonPath("$.previous7DayLoad").value(0.0))
                .andExpect(jsonPath("$.weeklyLoadChangePercent").value(nullValue()))
                .andExpect(jsonPath("$.monotony").value(closeTo(1.0 / Math.sqrt(6.0), 1e-9)))   // one active day of seven
                .andExpect(jsonPath("$.strain").value(closeTo(100.0 / Math.sqrt(6.0), 1e-9)));
    }

    @Test
    void malformedDateIsRejectedWithTheStructuredError() throws Exception {
        mockMvc.perform(get("/api/v1/training-state").param("date", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.timestamp").exists());
    }
}
