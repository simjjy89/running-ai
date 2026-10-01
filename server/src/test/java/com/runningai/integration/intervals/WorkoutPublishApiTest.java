package com.runningai.integration.intervals;

import com.runningai.integration.intervals.IntervalsException.Reason;
import com.runningai.training.CandidateTrainingType;
import com.runningai.training.HeartRateTarget;
import com.runningai.training.IntensityClass;
import com.runningai.training.PrimaryTargetType;
import com.runningai.training.SegmentType;
import com.runningai.training.TargetAvailability;
import com.runningai.training.TargetedWorkoutPrescription;
import com.runningai.training.TargetedWorkoutSegment;
import com.runningai.training.WorkoutIntensityTargetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract of the manual publish trigger. The publishing switch is pinned on, the Intervals key is blank and the
 * URL unreachable, and the publisher and prescription source are mocks: no network and no real calendar can be touched.
 */
@SpringBootTest(properties = {"running-ai.workout-publishing.enabled=true", "running-ai.intervals.api-key=",
        "running-ai.intervals.base-url=http://127.0.0.1:9"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkoutPublishApiTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 2);
    private static final String BODY = "{\"date\":\"2026-10-02\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IntervalsWorkoutPublisher publisher;

    @MockitoBean
    private WorkoutIntensityTargetService targetService;

    @BeforeEach
    void setUp() {
        HeartRateTarget hr = new HeartRateTarget(65, 78, 104, 125);
        List<TargetedWorkoutSegment> segments = List.of(
                new TargetedWorkoutSegment(SegmentType.WARM_UP, 5, IntensityClass.VERY_EASY, "warm-up",
                        PrimaryTargetType.HEART_RATE, null, hr, null),
                new TargetedWorkoutSegment(SegmentType.MAIN, 3, IntensityClass.EASY, "main",
                        PrimaryTargetType.HEART_RATE, null, hr, null));
        when(targetService.targetedPrescribe(DATE)).thenReturn(new TargetedWorkoutPrescription(DATE,
                CandidateTrainingType.EASY, 8, segments, TargetAvailability.FULL, null, null));
    }

    private void publisherReturns(IntervalsPublishOperation operation) {
        when(publisher.publish(any(), any()))
                .thenReturn(new IntervalsPublishResult(operation, "remote-id-not-exposed", true, DATE));
    }

    @ParameterizedTest
    @CsvSource({"CREATED", "UPDATED", "NO_CHANGE"})
    void publishesAndReportsTheOperation(IntervalsPublishOperation operation) throws Exception {
        publisherReturns(operation);

        mockMvc.perform(post("/api/v1/workout-publish").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-10-02"))
                .andExpect(jsonPath("$.operation").value(operation.name()))
                .andExpect(jsonPath("$.verified").value(true))
                .andExpect(jsonPath("$.intent").value("EASY"))
                .andExpect(jsonPath("$.stepCount").value(2))
                .andExpect(jsonPath("$.remoteEventId").doesNotExist());
    }

    @Test
    void missingDateIsABadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/workout-publish").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(publisher, never()).publish(any(), any());
    }

    @Test
    void unparsableDateIsABadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/workout-publish").contentType(MediaType.APPLICATION_JSON).content("{\"date\":\"tomorrow\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void getNeverPublishes() throws Exception {
        // Not pinned to 405: the existing global catch-all currently answers unsupported methods with 500.
        mockMvc.perform(get("/api/v1/workout-publish"))
                .andExpect(result -> assertThat(result.getResponse().getStatus() / 100).isNotEqualTo(2));

        verify(publisher, never()).publish(any(), any());
    }

    @ParameterizedTest
    @CsvSource({
            "AUTH_FAILED,401,INTERVALS_AUTH_FAILED",
            "FORBIDDEN,403,INTERVALS_FORBIDDEN",
            "RATE_LIMITED,429,INTERVALS_RATE_LIMITED",
            "NOT_CONFIGURED,503,INTERVALS_NOT_CONFIGURED",
            "CONNECTION_FAILED,503,INTERVALS_CONNECTION_FAILED",
            "TIMEOUT,504,INTERVALS_TIMEOUT",
            "UPSTREAM_ERROR,502,INTERVALS_UPSTREAM_ERROR",
            "READBACK_MISMATCH,502,INTERVALS_READBACK_MISMATCH",
            "DUPLICATE_OWNED_WORKOUT,409,INTERVALS_DUPLICATE_OWNED_WORKOUT",
            "UNMANAGED_WORKOUT_CONFLICT,409,INTERVALS_UNMANAGED_WORKOUT_CONFLICT",
            "EMPTY_WORKOUT,422,INTERVALS_EMPTY_WORKOUT"})
    void mapsIntervalsFailures(Reason reason, int httpStatus, String code) throws Exception {
        when(publisher.publish(any(), any())).thenThrow(new IntervalsException(reason, null, "Intervals failure"));

        mockMvc.perform(post("/api/v1/workout-publish").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().is(httpStatus))
                .andExpect(jsonPath("$.code").value(code));
    }
}
