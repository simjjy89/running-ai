package com.runningai.training;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A QUALITY recommendation (never produced in production) must still map to the same 422 on this endpoint. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkoutIntensityTargetQualityApiTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WorkoutRecommendationService recommendationService;

    @Test
    void qualityRecommendationIsUnprocessable() throws Exception {
        LocalDate day = LocalDate.of(2026, 9, 30);
        when(recommendationService.today()).thenReturn(day);
        when(recommendationService.recommend(any())).thenReturn(new WorkoutRecommendation(day,
                CandidateTrainingType.QUALITY, 30, 70, IntensityClass.HARD, RecommendationConfidence.LOW,
                DataSufficiency.LOW, List.of(), "test", null));

        mockMvc.perform(get("/api/v1/workout-intensity-targets"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("QUALITY_PRESCRIPTION_NOT_SUPPORTED"))
                .andExpect(jsonPath("$.segments").doesNotExist());
    }
}
