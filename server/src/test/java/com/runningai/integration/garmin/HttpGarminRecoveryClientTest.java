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

import java.net.ConnectException;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** HTTP contract with the connector's {@code /recovery}, against a mock server (no connector, no Garmin). */
class HttpGarminRecoveryClientTest {

    private static final String BASE_URL = "http://127.0.0.1:8765";
    private static final LocalDate DAY = LocalDate.of(2026, 10, 2);

    private MockRestServiceServer server;
    private HttpGarminRecoveryClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new HttpGarminRecoveryClient(builder.build(), new ObjectMapper());
    }

    @Test
    void requestsExactlyOneDayOnce() {
        server.expect(once(), requestTo(BASE_URL + "/recovery?date=2026-10-02")).andExpect(method(GET))
                .andRespond(withSuccess(GarminRecoveryMapperTest.FULL, MediaType.APPLICATION_JSON));

        JsonNode body = client.fetchDay(DAY);

        assertThat(body.path("date").asText()).isEqualTo("2026-10-02");
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
    void mapsConnectorErrorsAndNeverRetries(int status, String code, Reason expected) {
        server.expect(once(), requestTo(BASE_URL + "/recovery?date=2026-10-02"))
                .andRespond(withStatus(HttpStatus.valueOf(status)).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"" + code + "\",\"message\":\"m\"}"));

        assertThatThrownBy(() -> client.fetchDay(DAY))
                .isInstanceOf(GarminConnectorException.class)
                .extracting(e -> ((GarminConnectorException) e).getReason())
                .isEqualTo(expected);
        server.verify();
    }

    @Test
    void unreachableConnectorIsUnavailable() {
        server.expect(requestTo(BASE_URL + "/recovery?date=2026-10-02")).andRespond(withException(new ConnectException("refused")));

        assertThatThrownBy(() -> client.fetchDay(DAY))
                .isInstanceOf(GarminConnectorException.class)
                .extracting(e -> ((GarminConnectorException) e).getReason())
                .isEqualTo(Reason.UNAVAILABLE);
    }

    @Test
    void nonObjectBodyIsInvalid() {
        server.expect(requestTo(BASE_URL + "/recovery?date=2026-10-02")).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.fetchDay(DAY))
                .isInstanceOf(GarminConnectorException.class)
                .extracting(e -> ((GarminConnectorException) e).getReason())
                .isEqualTo(Reason.INVALID_RESPONSE);
    }
}
