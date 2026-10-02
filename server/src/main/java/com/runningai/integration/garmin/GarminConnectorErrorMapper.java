package com.runningai.integration.garmin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runningai.integration.garmin.GarminConnectorException.Reason;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;

/**
 * Shared connector-error translation used by every {@code Http*GarminSource}: a connector HTTP
 * status (and, when present, its {@code {code,message}} body) becomes a {@link GarminConnectorException};
 * an unreachable connector becomes {@link GarminConnectorException.Reason#UNAVAILABLE}.
 */
final class GarminConnectorErrorMapper {

    private GarminConnectorErrorMapper() {
    }

    static GarminConnectorException toConnectorException(RestClientResponseException e, ObjectMapper objectMapper) {
        int status = e.getStatusCode().value();
        String code = null;
        String message = null;
        try {
            JsonNode error = objectMapper.readTree(e.getResponseBodyAsString());
            if (error != null && error.isObject()) {
                code = error.path("code").asText(null);
                message = error.path("message").asText(null);
            }
        } catch (IOException | RuntimeException ignored) {
            // non-JSON error body: fall back to the status code alone
        }
        Reason reason = switch (status) {
            case 401 -> Reason.AUTH_REQUIRED;
            case 403 -> Reason.FORBIDDEN;
            case 429 -> Reason.RATE_LIMITED;
            case 404 -> Reason.NOT_FOUND;
            case 502 -> Reason.UPSTREAM_ERROR;
            default -> Reason.CONNECTOR_ERROR;
        };
        String text = "Garmin connector responded " + status
                + (code != null ? " " + code : "")
                + (message != null ? ": " + message : "");
        return new GarminConnectorException(reason, status, text, e);
    }

    static GarminConnectorException unavailable(ResourceAccessException e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        return new GarminConnectorException(Reason.UNAVAILABLE, null,
                "Garmin connector not reachable: " + cause.getClass().getSimpleName(), e);
    }
}
