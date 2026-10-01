package com.runningai.integration.intervals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** With the switch off, the real application service refuses end to end and never reaches the publisher. */
@SpringBootTest(properties = {"running-ai.workout-publishing.enabled=false", "running-ai.intervals.api-key=",
        "running-ai.intervals.base-url=http://127.0.0.1:9"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkoutPublishSwitchOffApiTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IntervalsWorkoutPublisher publisher;

    @Test
    void realServiceRefusesWhenSwitchedOff() throws Exception {
        mockMvc.perform(post("/api/v1/workout-publish").contentType(MediaType.APPLICATION_JSON).content("{\"date\":\"2026-10-02\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKOUT_PUBLISHING_DISABLED"));

        verify(publisher, never()).publish(any(), any());
    }
}
