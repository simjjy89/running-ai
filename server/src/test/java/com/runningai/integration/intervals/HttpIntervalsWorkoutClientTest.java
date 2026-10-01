package com.runningai.integration.intervals;

import com.runningai.integration.intervals.IntervalsException.Reason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** HTTP contract with Intervals.icu against a mock server: no network, synthetic credentials only. */
class HttpIntervalsWorkoutClientTest {

    private static final String BASE_URL = "https://intervals.test";
    private static final String API_KEY = "test-api-key";
    private static final LocalDate DATE = LocalDate.of(2026, 10, 1);
    private static final String EVENTS = BASE_URL + "/api/v1/athlete/0/events";
    private static final String BASIC = "Basic " + Base64.getEncoder()
            .encodeToString(("API_KEY:" + API_KEY).getBytes(StandardCharsets.UTF_8));

    private MockRestServiceServer server;
    private HttpIntervalsWorkoutClient client;

    private static IntervalsProperties properties(String apiKey) {
        return new IntervalsProperties(BASE_URL, "0", apiKey, Duration.ofSeconds(3), Duration.ofSeconds(15));
    }

    private void init(String apiKey) {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new HttpIntervalsWorkoutClient(builder.build(), properties(apiKey));
    }

    @BeforeEach
    void setUp() {
        init(API_KEY);
    }

    private static final String EVENT_JSON = """
            {"id": 91001, "category": "WORKOUT", "start_date_local": "2026-10-01T00:00:00", "type": "Run",
             "name": "RunningAI workout", "description": "- Warm up 10m\\n- 30m", "external_id": "runningai:workout:v1:0:2026-10-01",
             "unknown_field": {"x": 1}}
            """;

    private static IntervalsEventDraft draft() {
        return new IntervalsEventDraft(DATE, "RunningAI workout", "Run", "- Warm up 10m\n- 30m", "runningai:workout:v1:0:2026-10-01");
    }

    @Test
    void listSendsBasicAuthAndAOneDayWorkoutWindowAndMapsEvents() {
        server.expect(requestTo(EVENTS + "?oldest=2026-10-01&newest=2026-10-01&category=WORKOUT"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", BASIC))
                .andRespond(withSuccess("[" + EVENT_JSON + "]", MediaType.APPLICATION_JSON));

        List<IntervalsEvent> events = client.listWorkouts(DATE);

        assertThat(events).hasSize(1);
        IntervalsEvent e = events.get(0);
        assertThat(e.id()).isEqualTo("91001");
        assertThat(e.scheduledDate()).isEqualTo(DATE);
        assertThat(e.type()).isEqualTo("Run");
        assertThat(e.name()).isEqualTo("RunningAI workout");
        assertThat(e.description()).isEqualTo("- Warm up 10m\n- 30m");
        assertThat(e.externalId()).isEqualTo("runningai:workout:v1:0:2026-10-01");
        server.verify();
    }

    @Test
    void missingOptionalFieldsAreNull() {
        server.expect(requestTo(EVENTS + "/5"))
                .andRespond(withSuccess("{\"id\": 5, \"start_date_local\": \"2026-10-01T07:30:00\", \"description\": null}",
                        MediaType.APPLICATION_JSON));

        IntervalsEvent e = client.get("5");

        assertThat(e.scheduledDate()).isEqualTo(DATE);
        assertThat(e.description()).isNull();
        assertThat(e.externalId()).isNull();
        assertThat(e.name()).isNull();
    }

    @Test
    void getReadsOneEventById() {
        server.expect(requestTo(EVENTS + "/91001")).andExpect(method(HttpMethod.GET)).andExpect(header("Authorization", BASIC))
                .andRespond(withSuccess(EVENT_JSON, MediaType.APPLICATION_JSON));

        assertThat(client.get("91001").id()).isEqualTo("91001");
        server.verify();
    }

    @Test
    void createPostsTheWorkoutEventBodyOnce() {
        server.expect(requestTo(EVENTS)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", BASIC))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.category").value("WORKOUT"))
                .andExpect(jsonPath("$.start_date_local").value("2026-10-01T00:00:00"))
                .andExpect(jsonPath("$.type").value("Run"))
                .andExpect(jsonPath("$.name").value("RunningAI workout"))
                .andExpect(jsonPath("$.description").value("- Warm up 10m\n- 30m"))
                .andExpect(jsonPath("$.external_id").value("runningai:workout:v1:0:2026-10-01"))
                .andRespond(withStatus(HttpStatus.CREATED).body(EVENT_JSON).contentType(MediaType.APPLICATION_JSON));

        assertThat(client.create(draft()).id()).isEqualTo("91001");
        server.verify();
    }

    @Test
    void updatePutsToTheExistingEventId() {
        server.expect(requestTo(EVENTS + "/91001")).andExpect(method(HttpMethod.PUT))
                .andExpect(header("Authorization", BASIC))
                .andExpect(jsonPath("$.description").value("- Warm up 10m\n- 30m"))
                .andExpect(jsonPath("$.external_id").value("runningai:workout:v1:0:2026-10-01"))
                .andRespond(withSuccess(EVENT_JSON, MediaType.APPLICATION_JSON));

        assertThat(client.update("91001", draft()).id()).isEqualTo("91001");
        server.verify();
    }

    @Test
    void aMissingApiKeyFailsBeforeAnyRequestIsSent() {
        init("");     // the mock server has no expectation: any request would fail the test

        for (Runnable call : List.<Runnable>of(() -> client.listWorkouts(DATE), () -> client.get("1"),
                () -> client.create(draft()), () -> client.update("1", draft()))) {
            assertThatThrownBy(call::run).isInstanceOfSatisfying(IntervalsException.class,
                    e -> assertThat(e.getReason()).isEqualTo(Reason.NOT_CONFIGURED));
        }
        server.verify();
    }

    @ParameterizedTest
    @CsvSource({"401,AUTH_FAILED", "403,FORBIDDEN", "429,RATE_LIMITED", "400,CLIENT_ERROR", "404,CLIENT_ERROR",
            "422,CLIENT_ERROR", "500,UPSTREAM_ERROR", "502,UPSTREAM_ERROR", "503,UPSTREAM_ERROR"})
    void httpStatusesAreClassifiedAndSentExactlyOnce(int status, Reason reason) {
        server.expect(requestTo(EVENTS)).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.valueOf(status)).body("secret-response-body").contentType(MediaType.TEXT_PLAIN));

        assertThatThrownBy(() -> client.create(draft())).isInstanceOfSatisfying(IntervalsException.class, e -> {
            assertThat(e.getReason()).isEqualTo(reason);
            assertThat(e.getHttpStatus()).isEqualTo(status);
            assertThat(e.getCode()).isEqualTo("INTERVALS_" + reason.name());
            assertThat(e.getMessage()).doesNotContain("secret-response-body").doesNotContain(API_KEY);
        });
        server.verify();      // exactly one request: a second POST would have failed the mock
    }

    @Test
    void timeoutIsClassifiedAndNotRetried() {
        server.expect(requestTo(EVENTS)).andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> client.create(draft())).isInstanceOfSatisfying(IntervalsException.class, e -> {
            assertThat(e.getReason()).isEqualTo(Reason.TIMEOUT);
            assertThat(e.isOutcomeUnknown()).isTrue();
        });
        server.verify();
    }

    @Test
    void connectionFailureIsClassified() {
        server.expect(requestTo(EVENTS + "?oldest=2026-10-01&newest=2026-10-01&category=WORKOUT"))
                .andRespond(withException(new ConnectException("Connection refused")));

        assertThatThrownBy(() -> client.listWorkouts(DATE)).isInstanceOfSatisfying(IntervalsException.class,
                e -> assertThat(e.getReason()).isEqualTo(Reason.CONNECTION_FAILED));
    }

    @Test
    void unexpectedSuccessBodiesAreInvalidResponses() {
        server.expect(requestTo(EVENTS + "?oldest=2026-10-01&newest=2026-10-01&category=WORKOUT"))
                .andRespond(withSuccess("{\"not\": \"an array\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(EVENTS)).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"no_id\": true}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(EVENTS + "/7"))
                .andRespond(withSuccess("{\"id\": 7, \"start_date_local\": \"garbage\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.listWorkouts(DATE)).isInstanceOfSatisfying(IntervalsException.class,
                e -> assertThat(e.getReason()).isEqualTo(Reason.INVALID_RESPONSE));
        assertThatThrownBy(() -> client.create(draft())).isInstanceOfSatisfying(IntervalsException.class,
                e -> assertThat(e.getReason()).isEqualTo(Reason.INVALID_RESPONSE));
        assertThatThrownBy(() -> client.get("7")).isInstanceOfSatisfying(IntervalsException.class,
                e -> assertThat(e.getReason()).isEqualTo(Reason.INVALID_RESPONSE));
    }

    @Test
    void outcomeUnknownCoversOnlyTimeoutConnectionAndServerErrors() {
        for (Reason reason : Reason.values()) {
            boolean unknown = new IntervalsException(reason, null, "x").isOutcomeUnknown();
            assertThat(unknown).isEqualTo(reason == Reason.TIMEOUT || reason == Reason.CONNECTION_FAILED
                    || reason == Reason.UPSTREAM_ERROR);
        }
    }

    @Test
    void secretsAndWorkoutTextNeverAppearInToString() {
        assertThat(properties(API_KEY).toString()).doesNotContain(API_KEY).contains("<set>");
        assertThat(properties("").toString()).contains("<not set>");
        assertThat(draft().toString()).doesNotContain("Warm up");
    }
}
