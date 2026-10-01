package com.runningai.integration.garmin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runningai.integration.garmin.GarminConnectorException.Reason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * HTTP contract with the Python connector's {@code /lactate-threshold}, verified against a mock
 * server: no connector process, no Garmin, no network.
 */
class HttpGarminLactateThresholdSourceTest {

    private static final String BASE_URL = "http://127.0.0.1:8765";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockRestServiceServer server;
    private HttpGarminLactateThresholdSource source;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        source = new HttpGarminLactateThresholdSource(builder.build(), objectMapper);
    }

    @Test
    void parsesRawObjectUnchanged() {
        String body = """
                {"speed_and_heart_rate": {"heartRate": 180, "speed": 0.34444348}, "power": {"functionalThresholdPower": 300}}
                """;
        server.expect(once(), requestTo(BASE_URL + "/lactate-threshold")).andExpect(method(GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        JsonNode result = source.fetchLatest();

        assertThat(result.at("/speed_and_heart_rate/heartRate").asInt()).isEqualTo(180);
        assertThat(result.at("/power/functionalThresholdPower").asInt()).isEqualTo(300);
        server.verify();
    }

    @Test
    void nullSubFieldsPassThroughUnchanged() {
        server.expect(requestTo(BASE_URL + "/lactate-threshold"))
                .andRespond(withSuccess("{\"speed_and_heart_rate\": {\"heartRate\": null, \"speed\": null}}",
                        MediaType.APPLICATION_JSON));

        JsonNode result = source.fetchLatest();

        assertThat(result.at("/speed_and_heart_rate/heartRate").isNull()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "401, GARMIN_AUTH_REQUIRED, AUTH_REQUIRED",
            "403, GARMIN_FORBIDDEN, FORBIDDEN",
            "429, GARMIN_RATE_LIMITED, RATE_LIMITED",
            "502, GARMIN_UPSTREAM_ERROR, UPSTREAM_ERROR",
            "500, GARMIN_CONNECTOR_ERROR, CONNECTOR_ERROR"
    })
    void mapsConnectorErrorContractToReasons(int status, String code, Reason expected) {
        server.expect(once(), requestTo(BASE_URL + "/lactate-threshold"))
                .andRespond(withStatus(HttpStatus.valueOf(status))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\": \"" + code + "\", \"message\": \"connector says no\"}"));

        assertThatThrownBy(source::fetchLatest)
                .isInstanceOfSatisfying(GarminConnectorException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(expected);
                    assertThat(e.getHttpStatus()).isEqualTo(status);
                    assertThat(e.getMessage()).contains(code).contains("connector says no");
                });
        server.verify();
    }

    @Test
    void connectorDownOrTimeoutIsUnavailable() {
        server.expect(requestTo(BASE_URL + "/lactate-threshold"))
                .andRespond(withException(new SocketTimeoutException("read timed out")));

        assertThatThrownBy(source::fetchLatest)
                .isInstanceOfSatisfying(GarminConnectorException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(Reason.UNAVAILABLE);
                    assertThat(e.getHttpStatus()).isNull();
                });
    }

    @Test
    void nonObjectBodyIsRejected() {
        server.expect(requestTo(BASE_URL + "/lactate-threshold"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThatThrownBy(source::fetchLatest)
                .isInstanceOfSatisfying(GarminConnectorException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.INVALID_RESPONSE));
    }
}
