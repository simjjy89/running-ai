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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.GET;

/**
 * HTTP contract with the Python connector, verified against a mock server: no
 * connector process, no Garmin, no network.
 */
class HttpGarminActivitySourceTest {

    private static final String BASE_URL = "http://127.0.0.1:8765";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockRestServiceServer server;
    private HttpGarminActivitySource source;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        source = new HttpGarminActivitySource(builder.build(), objectMapper);
    }

    @Test
    void parsesJsonArrayIntoRawItemsUnchanged() {
        String body = """
                [{"activityId": 188081596, "activityType": {"typeKey": "running"}, "startTimeGMT": "2026-09-28 21:30:00",
                  "duration": 3600.0, "distance": 10000.0, "averageHR": 155.0, "privacy": {"typeKey": "private"}},
                 {"activityId": 188090001, "activityType": {"typeKey": "treadmill_running"}, "duration": 2400.5}]
                """;
        server.expect(once(), requestTo(BASE_URL + "/activities?start=0&limit=2")).andExpect(method(GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<JsonNode> items = source.fetchActivities(0, 2);

        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("activityId").asLong()).isEqualTo(188081596L);
        assertThat(items.get(0).at("/privacy/typeKey").asText()).isEqualTo("private");   // untouched nested fields
        assertThat(items.get(1).get("duration").asDouble()).isEqualTo(2400.5);
        server.verify();
    }

    @Test
    void emptyArrayIsAnEmptyList() {
        server.expect(requestTo(BASE_URL + "/activities?start=0&limit=5"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(source.fetchActivities(0, 5)).isEmpty();
    }

    @Test
    void nonZeroStartIsSentAsAQueryParam() {
        server.expect(once(), requestTo(BASE_URL + "/activities?start=50&limit=50"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(source.fetchActivities(50, 50)).isEmpty();
        server.verify();
    }

    @Test
    void rejectsNegativeStartWithoutCallingConnector() {
        assertThatThrownBy(() -> source.fetchActivities(-1, 10)).isInstanceOf(IllegalArgumentException.class);
        server.verify();
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
        server.expect(once(), requestTo(BASE_URL + "/activities?start=0&limit=1"))
                .andRespond(withStatus(HttpStatus.valueOf(status))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\": \"" + code + "\", \"message\": \"connector says no\"}"));

        assertThatThrownBy(() -> source.fetchActivities(0, 1))
                .isInstanceOfSatisfying(GarminConnectorException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(expected);
                    assertThat(e.getHttpStatus()).isEqualTo(status);
                    assertThat(e.getMessage()).contains(code).contains("connector says no");
                });
        server.verify();   // exactly one request: no automatic retry
    }

    @Test
    void nonJsonErrorBodyStillMapsByStatus() {
        server.expect(requestTo(BASE_URL + "/activities?start=0&limit=1"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("<html>nope</html>"));

        assertThatThrownBy(() -> source.fetchActivities(0, 1))
                .isInstanceOfSatisfying(GarminConnectorException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(Reason.CONNECTOR_ERROR);
                    assertThat(e.getHttpStatus()).isEqualTo(503);
                });
    }

    @Test
    void nonArrayBodyIsRejected() {
        server.expect(requestTo(BASE_URL + "/activities?start=0&limit=1"))
                .andRespond(withSuccess("{\"activityList\": []}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> source.fetchActivities(0, 1))
                .isInstanceOfSatisfying(GarminConnectorException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.INVALID_RESPONSE));
    }

    @Test
    void connectorDownOrTimeoutIsUnavailable() {
        server.expect(requestTo(BASE_URL + "/activities?start=0&limit=1"))
                .andRespond(withException(new SocketTimeoutException("read timed out")));

        assertThatThrownBy(() -> source.fetchActivities(0, 1))
                .isInstanceOfSatisfying(GarminConnectorException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(Reason.UNAVAILABLE);
                    assertThat(e.getHttpStatus()).isNull();
                    assertThat(e.getMessage()).contains("SocketTimeoutException");
                });
    }

    @Test
    void rejectsInvalidLimitWithoutCallingConnector() {
        assertThatThrownBy(() -> source.fetchActivities(0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> source.fetchActivities(0, 101)).isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }
}
