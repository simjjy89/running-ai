package com.runningai.integration.intervals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.runningai.integration.intervals.IntervalsException.Reason;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link IntervalsWorkoutClient} over {@link RestClient}.
 * <p>
 * Contract (legacy pipeline per the Phase 5C-0 investigation, plus the official Intervals.icu API docs):
 * HTTP Basic auth with user name {@code API_KEY} and the API key as password; athlete id {@code 0} means
 * the key's owner; {@code GET /api/v1/athlete/{id}/events?oldest&newest&category=WORKOUT},
 * {@code GET .../events/{eventId}}, {@code POST .../events}, {@code PUT .../events/{eventId}}; event JSON
 * fields {@code category, start_date_local (…T00:00:00), type, name, description, external_id}.
 * <p>
 * Exactly one request per call, no retry. Error messages contain the status code only, never the response
 * body, the API key or the workout text.
 */
@Component
public class HttpIntervalsWorkoutClient implements IntervalsWorkoutClient {

    static final String CATEGORY_WORKOUT = "WORKOUT";
    private static final String EVENTS = "/api/v1/athlete/{athleteId}/events";

    private final RestClient restClient;
    private final IntervalsProperties properties;

    public HttpIntervalsWorkoutClient(RestClient intervalsRestClient, IntervalsProperties properties) {
        this.restClient = intervalsRestClient;
        this.properties = properties;
    }

    @Override
    public List<IntervalsEvent> listWorkouts(LocalDate date) {
        String auth = authorization();
        JsonNode body = execute(() -> restClient.get()
                .uri(uri -> uri.path(EVENTS)
                        .queryParam("oldest", date)
                        .queryParam("newest", date)
                        .queryParam("category", CATEGORY_WORKOUT)
                        .build(properties.athleteId()))
                .header(HttpHeaders.AUTHORIZATION, auth)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(JsonNode.class));
        if (body == null || !body.isArray()) {
            throw new IntervalsException(Reason.INVALID_RESPONSE, 200, "Intervals event list was not a JSON array");
        }
        List<IntervalsEvent> events = new ArrayList<>(body.size());
        body.forEach(node -> events.add(toEvent(node)));
        return events;
    }

    @Override
    public IntervalsEvent get(String eventId) {
        String auth = authorization();
        JsonNode body = execute(() -> restClient.get()
                .uri(EVENTS + "/{eventId}", properties.athleteId(), eventId)
                .header(HttpHeaders.AUTHORIZATION, auth)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(JsonNode.class));
        return toEvent(body);
    }

    @Override
    public IntervalsEvent create(IntervalsEventDraft draft) {
        String auth = authorization();
        JsonNode body = execute(() -> restClient.post()
                .uri(EVENTS, properties.athleteId())
                .header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(payload(draft))
                .retrieve()
                .body(JsonNode.class));
        return toEvent(body);
    }

    @Override
    public IntervalsEvent update(String eventId, IntervalsEventDraft draft) {
        String auth = authorization();
        JsonNode body = execute(() -> restClient.put()
                .uri(EVENTS + "/{eventId}", properties.athleteId(), eventId)
                .header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(payload(draft))
                .retrieve()
                .body(JsonNode.class));
        return toEvent(body);
    }

    private String authorization() {
        return IntervalsHttp.authorization(properties);
    }

    private static ObjectNode payload(IntervalsEventDraft draft) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("category", CATEGORY_WORKOUT);
        node.put("start_date_local", draft.scheduledDate() + "T00:00:00");
        node.put("type", draft.type());
        node.put("name", draft.name());
        node.put("description", draft.description());
        node.put("external_id", draft.externalId());
        return node;
    }

    private static IntervalsEvent toEvent(JsonNode node) {
        if (node == null || !node.isObject() || !node.hasNonNull("id") || !node.hasNonNull("start_date_local")) {
            throw new IntervalsException(Reason.INVALID_RESPONSE, 200, "Intervals event had no id or start_date_local");
        }
        String start = node.get("start_date_local").asText();
        LocalDate date;
        try {
            date = LocalDate.parse(start.length() >= 10 ? start.substring(0, 10) : start);
        } catch (DateTimeParseException e) {
            throw new IntervalsException(Reason.INVALID_RESPONSE, 200, "Intervals event had an unreadable start_date_local");
        }
        return new IntervalsEvent(
                node.get("id").asText(),
                date,
                text(node, "name"),
                text(node, "type"),
                text(node, "description"),
                text(node, "external_id"));
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private interface Call {
        JsonNode run();
    }

    private static JsonNode execute(Call call) {
        return IntervalsHttp.execute(call::run);
    }
}
