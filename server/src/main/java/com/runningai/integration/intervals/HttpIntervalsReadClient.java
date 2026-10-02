package com.runningai.integration.intervals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.runningai.integration.intervals.IntervalsException.Reason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;

/**
 * {@link IntervalsReadClient} over {@link RestClient}. <strong>GET only.</strong>
 * <p>
 * Endpoints (Intervals.icu public API; athlete id {@code 0} means the API key's owner):
 * <ul>
 *   <li>{@code GET /api/v1/athlete/{athleteId}/activities?oldest&newest}</li>
 *   <li>{@code GET /api/v1/activity/{activityId}[?intervals=true]}</li>
 *   <li>{@code GET /api/v1/athlete/{athleteId}/wellness?oldest&newest}</li>
 * </ul>
 * One request per call, no retry, no automatic back-off - a 429 stops the caller.
 * <p>
 * Logging carries the endpoint, the status, an item count and the sanitized rate-limit headers. It never
 * carries the Authorization header, the API key, a response body or any athlete data.
 */
@Component
public class HttpIntervalsReadClient implements IntervalsReadClient {

    private static final String ACTIVITIES = "/api/v1/athlete/{athleteId}/activities";
    private static final String WELLNESS = "/api/v1/athlete/{athleteId}/wellness";
    private static final String ACTIVITY = "/api/v1/activity/{activityId}";

    private final Logger log = LoggerFactory.getLogger(HttpIntervalsReadClient.class);

    private final RestClient restClient;
    private final IntervalsProperties properties;

    public HttpIntervalsReadClient(RestClient intervalsRestClient, IntervalsProperties properties) {
        this.restClient = intervalsRestClient;
        this.properties = properties;
    }

    @Override
    public IntervalsReadResponse listActivities(LocalDate oldest, LocalDate newest) {
        requireRange(oldest, newest);
        return get("activities", uri -> uri.path(ACTIVITIES)
                .queryParam("oldest", oldest)
                .queryParam("newest", newest)
                .build(properties.athleteId()));
    }

    @Override
    public IntervalsReadResponse getActivity(String intervalsActivityId) {
        return getActivity(intervalsActivityId, false);
    }

    @Override
    public IntervalsReadResponse getActivity(String intervalsActivityId, boolean includeIntervals) {
        requireActivityId(intervalsActivityId);
        return get(includeIntervals ? "activity+intervals" : "activity", uri -> {
            uri.path(ACTIVITY);
            if (includeIntervals) {
                uri.queryParam("intervals", "true");
            }
            return uri.build(intervalsActivityId);
        });
    }

    @Override
    public IntervalsReadResponse getWellness(LocalDate oldest, LocalDate newest) {
        requireRange(oldest, newest);
        return get("wellness", uri -> uri.path(WELLNESS)
                .queryParam("oldest", oldest)
                .queryParam("newest", newest)
                .build(properties.athleteId()));
    }

    private IntervalsReadResponse get(String endpoint, UriBuilder uriBuilder) {
        String auth = IntervalsHttp.authorization(properties);
        ResponseEntity<JsonNode> response = IntervalsHttp.execute(() -> restClient.get()
                .uri(uriBuilder::build)
                .header(HttpHeaders.AUTHORIZATION, auth)
                .retrieve()
                .toEntity(JsonNode.class));

        JsonNode body = response.getBody() == null ? NullNode.getInstance() : response.getBody();
        if (!body.isObject() && !body.isArray() && !body.isNull()) {
            throw new IntervalsException(Reason.INVALID_RESPONSE, response.getStatusCode().value(),
                    "Intervals.icu returned a non-JSON-container body for " + endpoint);
        }
        IntervalsRateLimit rateLimit = IntervalsHttp.rateLimitOf(response.getHeaders());
        log.info("Intervals read completed: endpoint={} status={} items={} rateLimit={}",
                endpoint, response.getStatusCode().value(), body.isArray() ? body.size() : 1, rateLimit);
        return new IntervalsReadResponse(body, rateLimit);
    }

    private static void requireRange(LocalDate oldest, LocalDate newest) {
        if (oldest == null || newest == null) {
            throw new IllegalArgumentException("oldest and newest are required");
        }
        if (newest.isBefore(oldest)) {
            throw new IllegalArgumentException("newest must not be before oldest");
        }
    }

    private static void requireActivityId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Intervals activity id is required");
        }
    }

    /** Narrow seam so each endpoint states its own URI without repeating the GET plumbing. */
    @FunctionalInterface
    private interface UriBuilder {
        java.net.URI build(org.springframework.web.util.UriBuilder uri);
    }
}
