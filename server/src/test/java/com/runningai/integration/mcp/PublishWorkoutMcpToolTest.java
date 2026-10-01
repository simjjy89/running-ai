package com.runningai.integration.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.runningai.common.exception.UnprocessableRequestException;
import com.runningai.integration.intervals.IntervalsException;
import com.runningai.integration.intervals.IntervalsException.Reason;
import com.runningai.integration.intervals.IntervalsPublishOperation;
import com.runningai.integration.intervals.WorkoutPublishAlreadyRunningException;
import com.runningai.integration.intervals.WorkoutPublishApplicationService;
import com.runningai.integration.intervals.WorkoutPublishDisabledException;
import com.runningai.integration.intervals.WorkoutPublishResponse;
import com.runningai.training.CandidateTrainingType;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The tool adapter alone, with the application service mocked: no Spring context, no network. */
class PublishWorkoutMcpToolTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 2);

    private final WorkoutPublishApplicationService service = mock(WorkoutPublishApplicationService.class);
    private final PublishWorkoutMcpTool tool = new PublishWorkoutMcpTool(service, new ObjectMapper());

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(McpSchema.CallToolResult result) {
        return (Map<String, Object>) result.structuredContent();
    }

    private static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    // ---- success -------------------------------------------------------------------------------------------------------

    @Test
    void publishesTheExplicitDateExactlyOnceAndKeepsTheResultSemantics() {
        when(service.publish(DATE)).thenReturn(
                new WorkoutPublishResponse(DATE, IntervalsPublishOperation.CREATED, true, CandidateTrainingType.EASY, 3));

        McpSchema.CallToolResult result = tool.call(Map.of("date", "2026-10-02"));

        verify(service, times(1)).publish(DATE);
        assertThat(result.isError()).isFalse();
        assertThat(body(result)).containsEntry("success", true).containsEntry("date", "2026-10-02")
                .containsEntry("operation", "CREATED").containsEntry("verified", true)
                .containsEntry("intent", "EASY").containsEntry("stepCount", 3)
                .doesNotContainKeys("remoteEventId", "code");
        assertThat(text(result)).contains("\"operation\":\"CREATED\"").contains("\"success\":true");
    }

    @ParameterizedTest
    @EnumSource(IntervalsPublishOperation.class)
    void createdUpdatedAndNoChangeAreAllSuccesses(IntervalsPublishOperation operation) {
        when(service.publish(DATE)).thenReturn(
                new WorkoutPublishResponse(DATE, operation, true, CandidateTrainingType.EASY, 3));

        McpSchema.CallToolResult result = tool.call(Map.of("date", "2026-10-02"));

        assertThat(result.isError()).isFalse();
        assertThat(body(result)).containsEntry("success", true).containsEntry("operation", operation.name());
    }

    // ---- invalid input never reaches the service -----------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"tomorrow", "today", "next Monday", "2026-2-3", "02-10-2026", "2026/10/02", "2026-10-02T05:00",
            "2026-02-30", "2026-13-01", "", " 2026-10-02"})
    void anythingButAnExplicitIsoDateIsRejected(String date) {
        McpSchema.CallToolResult result = tool.call(Map.of("date", date));

        assertThat(result.isError()).isTrue();
        assertThat(body(result)).containsEntry("success", false).containsEntry("code", "INVALID_DATE");
        verifyNoInteractions(service);
    }

    @Test
    void missingDateIsRejected() {
        assertThat(body(tool.call(Map.of()))).containsEntry("code", "INVALID_ARGUMENT");
        assertThat(body(tool.call(null))).containsEntry("code", "INVALID_ARGUMENT");
        Map<String, Object> nullDate = new HashMap<>();
        nullDate.put("date", null);
        assertThat(body(tool.call(nullDate))).containsEntry("code", "INVALID_DATE");
        verifyNoInteractions(service);
    }

    @Test
    void nonStringDateIsRejected() {
        assertThat(body(tool.call(Map.of("date", 20261002)))).containsEntry("code", "INVALID_DATE");
        verifyNoInteractions(service);
    }

    @Test
    void extraArgumentsSuchAsCredentialsAreRejected() {
        McpSchema.CallToolResult result = tool.call(Map.of("date", "2026-10-02", "apiKey", "not-a-real-key"));

        assertThat(result.isError()).isTrue();
        assertThat(body(result)).containsEntry("code", "INVALID_ARGUMENT");
        assertThat(text(result)).doesNotContain("not-a-real-key");
        verifyNoInteractions(service);
    }

    // ---- errors keep their machine-readable code --------------------------------------------------------------------------

    @Test
    void disabledMasterSwitchIsReportedAsAnError() {
        when(service.publish(DATE)).thenThrow(new WorkoutPublishDisabledException());

        McpSchema.CallToolResult result = tool.call(Map.of("date", "2026-10-02"));

        assertThat(result.isError()).isTrue();
        assertThat(body(result)).containsEntry("success", false).containsEntry("code", "WORKOUT_PUBLISHING_DISABLED")
                .containsEntry("date", "2026-10-02");
    }

    @Test
    void alreadyRunningIsReportedAsAnError() {
        when(service.publish(DATE)).thenThrow(new WorkoutPublishAlreadyRunningException(DATE));

        assertThat(body(tool.call(Map.of("date", "2026-10-02")))).containsEntry("code", "WORKOUT_PUBLISH_ALREADY_RUNNING");
    }

    @ParameterizedTest
    @EnumSource(Reason.class)
    void everyIntervalsFailureKeepsItsCode(Reason reason) {
        when(service.publish(DATE)).thenThrow(new IntervalsException(reason, null, "Intervals failure"));

        McpSchema.CallToolResult result = tool.call(Map.of("date", "2026-10-02"));

        assertThat(result.isError()).isTrue();
        assertThat(body(result)).containsEntry("code", "INTERVALS_" + reason.name());
        verify(service, times(1)).publish(DATE);           // no retry, whatever the reason
    }

    @Test
    void domainErrorsKeepTheirCode() {
        when(service.publish(DATE)).thenThrow(new UnprocessableRequestException("WORKOUT_TYPE_NOT_SUPPORTED", "not supported"));

        assertThat(body(tool.call(Map.of("date", "2026-10-02")))).containsEntry("code", "WORKOUT_TYPE_NOT_SUPPORTED");
    }

    @Test
    void unexpectedFailuresExposeNoInternals() {
        when(service.publish(DATE)).thenThrow(new IllegalStateException("secret internal detail"));

        McpSchema.CallToolResult result = tool.call(Map.of("date", "2026-10-02"));

        assertThat(result.isError()).isTrue();
        assertThat(body(result)).containsEntry("code", "INTERNAL_ERROR");
        assertThat(text(result)).doesNotContain("secret internal detail").doesNotContain("IllegalStateException");
    }

    // ---- tool contract ------------------------------------------------------------------------------------------------------

    @Test
    void theToolContractIsOneExplicitDateArgument() {
        McpSchema.Tool definition = tool.specification().tool();

        assertThat(definition.name()).isEqualTo("publish_workout");
        assertThat(definition.description()).contains("explicit").contains("side effect").contains("idempotent");
        assertThat(definition.inputSchema().required()).containsExactly("date");
        assertThat(definition.inputSchema().properties()).containsOnlyKeys("date");
        assertThat(definition.inputSchema().additionalProperties()).isFalse();
        assertThat(definition.annotations().readOnlyHint()).isFalse();
        assertThat(definition.annotations().idempotentHint()).isTrue();
    }

    @Test
    void theSpecificationHandlerDelegatesToTheSameCall() {
        when(service.publish(DATE)).thenReturn(
                new WorkoutPublishResponse(DATE, IntervalsPublishOperation.NO_CHANGE, true, CandidateTrainingType.EASY, 3));

        McpSchema.CallToolResult result = tool.specification().callHandler()
                .apply(null, new McpSchema.CallToolRequest("publish_workout", Map.of("date", "2026-10-02")));

        assertThat(body(result)).containsEntry("operation", "NO_CHANGE");
    }

    // ---- architecture: the adapter knows only the application service -------------------------------------------------------

    @Test
    void dependsOnTheApplicationServiceOnly() {
        Constructor<?> constructor = PublishWorkoutMcpTool.class.getConstructors()[0];

        assertThat(Arrays.asList(constructor.getParameterTypes()))
                .containsExactlyInAnyOrder(WorkoutPublishApplicationService.class, ObjectMapper.class);
    }

    @Test
    void noMcpSourceImportsAPipelineStageOrTheIntervalsClient() throws IOException {
        List<String> forbidden = List.of("StructuredWorkoutMapper", "IntervalsWorkoutRenderer", "IntervalsWorkoutPublisher",
                "IntervalsWorkoutClient", "HttpIntervalsWorkoutClient", "WorkoutIntensityTargetService", "GarminSafeCueFormatter",
                "RestClient", "WorkoutPublishController");
        Path dir = Path.of("src/main/java/com/runningai/integration/mcp");

        try (Stream<Path> files = Files.list(dir)) {
            List<Path> sources = files.filter(p -> p.toString().endsWith(".java")).toList();
            assertThat(sources).isNotEmpty();
            for (Path source : sources) {
                String code = Files.readString(source);
                for (String name : forbidden) {
                    assertThat(code).as(source + " must not use " + name).doesNotContain(name);
                }
            }
        }
    }
}
