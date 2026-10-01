package com.runningai.integration.intervals;

import com.runningai.common.exception.ErrorResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps workout publish failures to HTTP errors; ordered ahead of the global handler so its catch-all does not turn
 * these into 500s. Messages carry no credentials, response bodies or workout text.
 */
@RestControllerAdvice(assignableTypes = WorkoutPublishController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WorkoutPublishExceptionHandler {

    @ExceptionHandler(WorkoutPublishDisabledException.class)
    public ResponseEntity<ErrorResponse> disabled(WorkoutPublishDisabledException e) {
        return body(HttpStatus.CONFLICT, "WORKOUT_PUBLISHING_DISABLED", e.getMessage());
    }

    @ExceptionHandler(WorkoutPublishAlreadyRunningException.class)
    public ResponseEntity<ErrorResponse> alreadyRunning(WorkoutPublishAlreadyRunningException e) {
        return body(HttpStatus.CONFLICT, "WORKOUT_PUBLISH_ALREADY_RUNNING", e.getMessage());
    }

    @ExceptionHandler(IntervalsException.class)
    public ResponseEntity<ErrorResponse> intervals(IntervalsException e) {
        HttpStatus status = switch (e.getReason()) {
            case AUTH_FAILED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case NOT_CONFIGURED, CONNECTION_FAILED -> HttpStatus.SERVICE_UNAVAILABLE;
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case CLIENT_ERROR, UPSTREAM_ERROR, INVALID_RESPONSE, READBACK_MISMATCH -> HttpStatus.BAD_GATEWAY;
            case DUPLICATE_OWNED_WORKOUT, UNMANAGED_WORKOUT_CONFLICT -> HttpStatus.CONFLICT;
            case EMPTY_WORKOUT -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return body(status, e.getCode(), e.getMessage());
    }

    private static ResponseEntity<ErrorResponse> body(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(ErrorResponse.of(code, message));
    }
}
