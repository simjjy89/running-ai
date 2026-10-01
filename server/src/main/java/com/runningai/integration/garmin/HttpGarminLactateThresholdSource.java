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

/**
 * {@link GarminLactateThresholdSource} backed by the localhost Python connector
 * ({@code GET /lactate-threshold}). Performs exactly one request per call and never retries;
 * connector errors are mapped to {@link GarminConnectorException} using the same translation as
 * {@link HttpGarminActivitySource}.
 */
@Component
public class HttpGarminLactateThresholdSource implements GarminLactateThresholdSource {

    private static final Logger log = LoggerFactory.getLogger(HttpGarminLactateThresholdSource.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public HttpGarminLactateThresholdSource(RestClient garminConnectorRestClient, ObjectMapper objectMapper) {
        this.restClient = garminConnectorRestClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public JsonNode fetchLatest() {
        JsonNode body;
        try {
            body = restClient.get()
                    .uri("/lactate-threshold")
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
        log.info("Garmin connector returned a lactate threshold snapshot");
        return body;
    }
}
