package com.runningai.integration.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runningai.common.exception.ResourceNotFoundException;
import com.runningai.common.exception.UnprocessableRequestException;
import com.runningai.integration.intervals.IntervalsException;
import com.runningai.integration.intervals.WorkoutPublishAlreadyRunningException;
import com.runningai.integration.intervals.WorkoutPublishApplicationService;
import com.runningai.integration.intervals.WorkoutPublishDisabledException;
import com.runningai.integration.intervals.WorkoutPublishResponse;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The MCP {@code publish_workout} tool: a transport adapter in front of {@link WorkoutPublishApplicationService}, exactly like
 * the manual REST trigger and the scheduler. Its only jobs are strict input validation, one {@code publish(date)} call and
 * result serialization; every safety rule (master switch, per-date single-flight, publisher idempotency, readback
 * verification, no retry) is the service's and is therefore never bypassed.
 * <p>
 * Input is an explicit ISO {@code yyyy-MM-dd} date only: no natural-language dates, no other arguments (in particular no
 * credentials). Success and failure are both returned as JSON with a machine-readable {@code code} on failure and
 * {@code isError=true}, so a client cannot mistake a refused or failed publish for a success. No stack traces are returned.
 */
public class PublishWorkoutMcpTool {

    public static final String NAME = "publish_workout";

    static final String DESCRIPTION = """
            Publish the RunningAI workout for an explicit calendar date through the canonical RunningAI Spring publishing \
            pipeline. This tool has an external side effect: it creates or updates the RunningAI-owned workout on that date \
            in the configured Intervals.icu account (which may then reach Garmin). Input: {"date": "YYYY-MM-DD"}, an explicit \
            ISO calendar date (the caller resolves words like "today" or "tomorrow"; this tool does not). The workout content \
            is decided by RunningAI for that date; it cannot be supplied here. Repeated calls for the same date are \
            idempotent: CREATED the first time, NO_CHANGE when the workout is unchanged, UPDATED in place when it changed. \
            On failure the result has isError=true, success=false and a machine-readable code (for example \
            WORKOUT_PUBLISHING_DISABLED); never treat such a result as a published workout.""";

    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Logger log = LoggerFactory.getLogger(PublishWorkoutMcpTool.class);

    private final WorkoutPublishApplicationService publishService;
    private final ObjectMapper objectMapper;

    public PublishWorkoutMcpTool(WorkoutPublishApplicationService publishService, ObjectMapper objectMapper) {
        this.publishService = publishService;
        this.objectMapper = objectMapper;
    }

    public McpStatelessServerFeatures.SyncToolSpecification specification() {
        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema("object",
                Map.of("date", Map.of(
                        "type", "string",
                        "format", "date",
                        "pattern", "^\\d{4}-\\d{2}-\\d{2}$",
                        "description", "Explicit ISO calendar date (YYYY-MM-DD) of the workout to publish")),
                List.of("date"), false, null, null);
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(NAME)
                .title("Publish RunningAI workout")
                .description(DESCRIPTION)
                .inputSchema(inputSchema)
                // not read-only; may overwrite the RunningAI-owned workout of that date; idempotent; reaches an external system
                .annotations(new McpSchema.ToolAnnotations("Publish RunningAI workout", false, true, true, true, null))
                .build();
        return McpStatelessServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((context, request) -> call(request.arguments()))
                .build();
    }

    McpSchema.CallToolResult call(Map<String, Object> arguments) {
        LocalDate date;
        try {
            date = parseDate(arguments);
        } catch (InvalidArgumentException e) {
            return failure(e.code, e.getMessage(), null);
        }
        try {
            WorkoutPublishResponse r = publishService.publish(date);
            log.info("MCP tool {}: date={} operation={} verified={}", NAME, date, r.operation(), r.verified());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("date", r.date().toString());
            body.put("operation", r.operation().name());
            body.put("verified", r.verified());
            body.put("intent", r.intent() == null ? null : r.intent().name());
            body.put("stepCount", r.stepCount());
            return result(body, false);
        } catch (WorkoutPublishDisabledException e) {
            return failure("WORKOUT_PUBLISHING_DISABLED", e.getMessage(), date);
        } catch (WorkoutPublishAlreadyRunningException e) {
            return failure("WORKOUT_PUBLISH_ALREADY_RUNNING", e.getMessage(), date);
        } catch (IntervalsException e) {
            return failure(e.getCode(), e.getMessage(), date);
        } catch (UnprocessableRequestException e) {
            return failure(e.getCode(), e.getMessage(), date);
        } catch (ResourceNotFoundException e) {
            return failure(e.getCode(), e.getMessage(), date);
        } catch (RuntimeException e) {
            log.error("MCP tool {} failed unexpectedly: date={} exception={}", NAME, date, e.getClass().getSimpleName());
            return failure("INTERNAL_ERROR", "Unexpected server error", date);
        }
    }

    private static LocalDate parseDate(Map<String, Object> arguments) {
        if (arguments == null || !arguments.containsKey("date")) {
            throw new InvalidArgumentException("INVALID_ARGUMENT", "date is required (YYYY-MM-DD)");
        }
        if (arguments.size() != 1) {
            throw new InvalidArgumentException("INVALID_ARGUMENT", "Only the argument 'date' is accepted");
        }
        Object raw = arguments.get("date");
        if (!(raw instanceof String text) || !ISO_DATE.matcher(text).matches()) {
            throw new InvalidArgumentException("INVALID_DATE", "date must be an explicit ISO calendar date (YYYY-MM-DD)");
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw new InvalidArgumentException("INVALID_DATE", "date is not a valid calendar date (YYYY-MM-DD)");
        }
    }

    private McpSchema.CallToolResult failure(String code, String message, LocalDate date) {
        if (date != null) {
            log.warn("MCP tool {} refused or failed: date={} code={}", NAME, date, code);
        } else {
            log.info("MCP tool {} rejected invalid input: code={}", NAME, code);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", code);
        body.put("message", message);
        if (date != null) {
            body.put("date", date.toString());
        }
        return result(body, true);
    }

    private McpSchema.CallToolResult result(Map<String, Object> body, boolean isError) {
        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("tool result could not be serialized", e);
        }
        return McpSchema.CallToolResult.builder()
                .addTextContent(json)
                .structuredContent(body)
                .isError(isError)
                .build();
    }

    private static final class InvalidArgumentException extends RuntimeException {

        private final String code;

        InvalidArgumentException(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
