package com.runningai.integration.garmin;

import com.runningai.common.exception.ErrorResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps Garmin sync failures to HTTP errors. Ordered ahead of the global handler so its
 * catch-all does not turn these into 500s. Messages carry no credentials or payloads.
 */
@RestControllerAdvice(assignableTypes = {GarminSyncController.class, GarminProfileSyncController.class,
        GarminRecoverySyncController.class, GarminActivityDetailController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GarminSyncExceptionHandler {

    @ExceptionHandler(GarminSyncAlreadyRunningException.class)
    public ResponseEntity<ErrorResponse> alreadyRunning(GarminSyncAlreadyRunningException e) {
        return body(HttpStatus.CONFLICT, "GARMIN_SYNC_ALREADY_RUNNING", e.getMessage());
    }

    @ExceptionHandler(GarminRecoverySyncAlreadyRunningException.class)
    public ResponseEntity<ErrorResponse> recoveryAlreadyRunning(GarminRecoverySyncAlreadyRunningException e) {
        return body(HttpStatus.CONFLICT, "GARMIN_RECOVERY_SYNC_ALREADY_RUNNING", e.getMessage());
    }

    @ExceptionHandler(GarminIncrementalSyncException.class)
    public ResponseEntity<ErrorResponse> incomplete(GarminIncrementalSyncException e) {
        return body(HttpStatus.SERVICE_UNAVAILABLE, "INCREMENTAL_WINDOW_INCOMPLETE", e.getMessage());
    }

    @ExceptionHandler(GarminConnectorException.class)
    public ResponseEntity<ErrorResponse> connector(GarminConnectorException e) {
        return switch (e.getReason()) {
            case AUTH_REQUIRED -> body(HttpStatus.UNAUTHORIZED, "GARMIN_AUTH_REQUIRED", e.getMessage());
            case FORBIDDEN -> body(HttpStatus.FORBIDDEN, "GARMIN_FORBIDDEN", e.getMessage());
            case RATE_LIMITED -> body(HttpStatus.TOO_MANY_REQUESTS, "GARMIN_RATE_LIMITED", e.getMessage());
            case UNAVAILABLE -> body(HttpStatus.SERVICE_UNAVAILABLE, "GARMIN_CONNECTOR_UNAVAILABLE", e.getMessage());
            case NOT_FOUND -> body(HttpStatus.NOT_FOUND, "GARMIN_NOT_FOUND", e.getMessage());
            case UPSTREAM_ERROR, CONNECTOR_ERROR, INVALID_RESPONSE ->
                    body(HttpStatus.BAD_GATEWAY, "GARMIN_UPSTREAM_ERROR", e.getMessage());
        };
    }

    private static ResponseEntity<ErrorResponse> body(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(ErrorResponse.of(code, message));
    }
}
