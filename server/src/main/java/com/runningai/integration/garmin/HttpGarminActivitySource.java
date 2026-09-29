package com.runningai.integration.garmin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runningai.integration.garmin.GarminConnectorException.Reason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link GarminActivitySource} backed by the localhost Python connector
 * ({@code GET /activities?limit=N}). Performs exactly one request per call and never
 * retries; connector errors are mapped to {@link GarminConnectorException}.
 */
@Component
public class HttpGarminActivitySource implements GarminActivitySource {

    private static final Logger log = LoggerFactory.getLogger(HttpGarminActivitySource.class);

    static final int MAX_LIMIT = 100;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public HttpGarminActivitySource(RestClient garminConnectorRestClient, ObjectMapper objectMapper) {
        this.restClient = garminConnectorRestClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<JsonNode> fetchActivities(int start, int limit) {
        if (start < 0) {
            throw new IllegalArgumentException("start must be >= 0");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        JsonNode body;
        try {
            body = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/activities")
                            .queryParam("start", start)
                            .queryParam("limit", limit)
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw toConnectorException(e);
        } catch (ResourceAccessException e) {
            throw new GarminConnectorException(Reason.UNAVAILABLE, null,
                    "Garmin connector not reachable: " + describe(e), e);
        }
        if (body == null || !body.isArray()) {
            throw new GarminConnectorException(Reason.INVALID_RESPONSE, 200,
                    "Garmin connector returned " + (body == null ? "an empty body" : "a non-array body"));
        }
        List<JsonNode> items = new ArrayList<>(body.size());
        body.forEach(items::add);
        log.info("Garmin connector returned {} activity item(s) (start={}, limit={})", items.size(), start, limit);
        return items;
    }

    private GarminConnectorException toConnectorException(RestClientResponseException e) {
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
            case 502 -> Reason.UPSTREAM_ERROR;
            default -> Reason.CONNECTOR_ERROR;
        };
        String text = "Garmin connector responded " + status
                + (code != null ? " " + code : "")
                + (message != null ? ": " + message : "");
        return new GarminConnectorException(reason, status, text, e);
    }

    private static String describe(ResourceAccessException e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        return cause.getClass().getSimpleName();
    }
}
