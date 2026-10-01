package com.runningai.integration.mcp;

import com.runningai.integration.intervals.IntervalsPublishOperation;
import com.runningai.integration.intervals.IntervalsPublishResult;
import com.runningai.integration.intervals.IntervalsWorkoutPublisher;
import com.runningai.integration.intervals.WorkoutPublishApplicationService;
import com.runningai.integration.intervals.WorkoutPublishResponse;
import com.runningai.training.CandidateTrainingType;
import com.runningai.training.HeartRateTarget;
import com.runningai.training.IntensityClass;
import com.runningai.training.PrimaryTargetType;
import com.runningai.training.SegmentType;
import com.runningai.training.TargetAvailability;
import com.runningai.training.TargetedWorkoutPrescription;
import com.runningai.training.TargetedWorkoutSegment;
import com.runningai.training.WorkoutIntensityTargetService;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * MCP and another trigger for the same date at the same time: the real application service's per-date guard (6A) rejects the
 * MCP call; there is no MCP-specific lock. The publisher is mocked, so nothing reaches Intervals.
 */
@SpringBootTest(properties = {"running-ai.mcp.enabled=true", "running-ai.workout-publishing.enabled=true",
        "running-ai.intervals.api-key=", "running-ai.intervals.base-url=http://127.0.0.1:9"})
@ActiveProfiles("test")
class McpSingleFlightTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 2);

    @Autowired
    private PublishWorkoutMcpTool tool;

    @Autowired
    private WorkoutPublishApplicationService service;

    @MockitoBean
    private IntervalsWorkoutPublisher publisher;

    @MockitoBean
    private WorkoutIntensityTargetService targetService;

    @Test
    @SuppressWarnings("unchecked")
    void sameDateWhileAnotherPublishRunsIsRejectedThenAllowed() throws Exception {
        HeartRateTarget hr = new HeartRateTarget(65, 78, 104, 125);
        TargetedWorkoutPrescription prescription = new TargetedWorkoutPrescription(DATE, CandidateTrainingType.EASY, 8,
                List.of(new TargetedWorkoutSegment(SegmentType.MAIN, 8, IntensityClass.EASY, "main",
                        PrimaryTargetType.HEART_RATE, null, hr, null)), TargetAvailability.FULL, null, null);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(targetService.targetedPrescribe(DATE)).thenAnswer(invocation -> {
            entered.countDown();
            release.await(10, TimeUnit.SECONDS);
            return prescription;
        });
        when(publisher.publish(any(), any()))
                .thenReturn(new IntervalsPublishResult(IntervalsPublishOperation.CREATED, "remote", true, DATE));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<WorkoutPublishResponse> other = executor.submit(() -> service.publish(DATE));   // e.g. a manual POST
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            McpSchema.CallToolResult rejected = tool.call(Map.of("date", "2026-10-02"));
            assertThat(rejected.isError()).isTrue();
            assertThat((Map<String, Object>) rejected.structuredContent()).containsEntry("code", "WORKOUT_PUBLISH_ALREADY_RUNNING");

            release.countDown();
            assertThat(other.get(5, TimeUnit.SECONDS).operation()).isEqualTo(IntervalsPublishOperation.CREATED);

            McpSchema.CallToolResult after = tool.call(Map.of("date", "2026-10-02"));
            assertThat(after.isError()).isFalse();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
