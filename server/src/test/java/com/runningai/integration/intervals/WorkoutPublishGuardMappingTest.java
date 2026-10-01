package com.runningai.integration.intervals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Error mapping of the two operational guards (disabled, already running) with the application service mocked. */
@SpringBootTest(properties = {"running-ai.workout-publishing.enabled=true", "running-ai.intervals.api-key=",
        "running-ai.intervals.base-url=http://127.0.0.1:9"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkoutPublishGuardMappingTest {

    private static final String BODY = "{\"date\":\"2026-10-02\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WorkoutPublishApplicationService service;

    @Test
    void disabledIsAConflictNotASecurityError() throws Exception {
        when(service.publish(any())).thenThrow(new WorkoutPublishDisabledException());

        mockMvc.perform(post("/api/v1/workout-publish").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKOUT_PUBLISHING_DISABLED"));
    }

    @Test
    void alreadyRunningIsAConflict() throws Exception {
        when(service.publish(any())).thenThrow(new WorkoutPublishAlreadyRunningException(LocalDate.of(2026, 10, 2)));

        mockMvc.perform(post("/api/v1/workout-publish").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKOUT_PUBLISH_ALREADY_RUNNING"));
    }
}
