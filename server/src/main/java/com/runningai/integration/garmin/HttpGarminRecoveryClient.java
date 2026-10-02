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

import java.time.LocalDate;

/**
 * {@link GarminRecoveryClient} backed by the localhost Python connector ({@code GET /recovery?date=}).
 * Exactly one request per call, never retried; errors use the shared connector translation.
 */
@Component
public class HttpGarminRecoveryClient implements GarminRecoveryClient {

    private static final Logger log = LoggerFactory.getLogger(HttpGarminRecoveryClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public HttpGarminRecoveryClient(RestClient garminConnectorRestClient, ObjectMapper objectMapper) {
        this.restClient = garminConnectorRestClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public JsonNode fetchDay(LocalDate date) {
        JsonNode body;
        try {
            body = restClient.get()
                    .uri(uri -> uri.path("/recovery").queryParam("date", date.toString()).build())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw GarminConnectorErrorMapper.toConnectorException(e, objectMapper);
        } catch (ResourceAccessException e) {
            throw GarminConnectorErrorMapper.unavailable(e);
        }
        if (body == null || !body.isObject()) {
            throw new GarminConnectorException(Reason.INVALID_RESPONSE, 200,
                    "Garmin connector returned " + (body == null ? "an empty body" : "a non-object body"));
        }
        log.info("Garmin connector returned recovery metrics for {}", date);
        return body;
    }
}
