package com.runningai.integration.intervals;

import com.runningai.integration.intervals.IntervalsException.Reason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.lang.reflect.Method;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.GET;

/**
 * HTTP contract of the read-only Intervals.icu client (Phase 6H-5), against a mock server.
 * <p>
 * The point of this class is as much what the client <em>cannot</em> do as what it does: every call is a
 * GET, there is no write method to call, and nothing is ever sent twice.
 */
class HttpIntervalsReadClientTest {

    private static final String BASE = "https://intervals.icu";
    // "API_KEY:test-key" base64-encoded; a synthetic key, never a real one
    private static final String EXPECTED_AUTH = "Basic QVBJX0tFWTp0ZXN0LWtleQ==";

    private record Fixture(MockRestServiceServer server, HttpIntervalsReadClient client) {
    }

    private Fixture client(String apiKey) {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        IntervalsProperties properties = new IntervalsProperties(
                BASE, "0", apiKey, Duration.ofSeconds(3), Duration.ofSeconds(15));
        return new Fixture(server, new HttpIntervalsReadClient(builder.build(), properties));
    }

    private Fixture client() {
        return client("test-key");
    }

    // ---- structural: this client cannot write -------------------------------------------------------

    @Test
    void theReadInterfaceDeclaresNoWriteOperation() {
        List<String> methods = Arrays.stream(IntervalsReadClient.class.getDeclaredMethods())
                .map(Method::getName)
                .distinct()
                .toList();

        assertThat(methods).containsExactlyInAnyOrder("listActivities", "getActivity", "getWellness");
        assertThat(methods).noneMatch(name -> name.matches("(?i).*(create|update|delete|publish|post|put|write|save).*"));
    }

    @Test
    void everyOperationIsAGet() {
        Fixture f = client();
        LocalDate day = LocalDate.parse("2026-09-28");
        f.server().expect(once(), requestTo(BASE + "/api/v1/athlete/0/activities?oldest=2026-09-28&newest=2026-09-28"))
                .andExpect(method(GET)).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        f.server().expect(once(), requestTo(BASE + "/api/v1/activity/i12345"))
                .andExpect(method(GET)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        f.server().expect(once(), requestTo(BASE + "/api/v1/activity/i12345?intervals=true"))
                .andExpect(method(GET)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        f.server().expect(once(), requestTo(BASE + "/api/v1/athlete/0/wellness?oldest=2026-09-28&newest=2026-09-28"))
                .andExpect(method(GET)).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        f.client().listActivities(day, day);
        f.client().getActivity("i12345");
        f.client().getActivity("i12345", true);
        f.client().getWellness(day, day);

        f.server().verify();
    }

    // ---- requests -----------------------------------------------------------------------------------

    @Test
    void theActivityListUsesTheDateRangeAndTheKeyOwnersAthleteId() {
        Fixture f = client();
        f.server().expect(once(), requestTo(BASE + "/api/v1/athlete/0/activities?oldest=2026-09-18&newest=2026-09-30"))
                .andExpect(method(GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, EXPECTED_AUTH))
                .andRespond(withSuccess("[{\"any\":1},{\"any\":2}]", MediaType.APPLICATION_JSON));

        IntervalsReadResponse response = f.client()
                .listActivities(LocalDate.parse("2026-09-18"), LocalDate.parse("2026-09-30"));

        assertThat(response.isArray()).isTrue();
        assertThat(response.size()).isEqualTo(2);
        f.server().verify();
    }

    @Test
    void theBodyIsReturnedUntouched() {
        Fixture f = client();
        String raw = "{\"id\":\"i1\",\"nested\":{\"x\":null},\"unexpected_field\":[1,2]}";
        f.server().expect(once(), requestTo(BASE + "/api/v1/activity/i1"))
                .andRespond(withSuccess(raw, MediaType.APPLICATION_JSON));

        IntervalsReadResponse response = f.client().getActivity("i1");

        // nothing is narrowed or renamed: the raw payload is what gets stored first
        assertThat(response.body().toString()).isEqualTo(raw);
        f.server().verify();
    }

    @Test
    void intervalsAreOnlyRequestedWhenAskedFor() {
        Fixture f = client();
        f.server().expect(once(), requestTo(BASE + "/api/v1/activity/i1"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        f.client().getActivity("i1", false);

        f.server().verify();
    }

    @Test
    void rateLimitHeadersAreReportedWhenPresentAndUnknownWhenNot() {
        Fixture f = client();
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-RateLimit-Limit", "2500");
        headers.add("X-RateLimit-Remaining", "2487");
        f.server().expect(once(), requestTo(BASE + "/api/v1/activity/i1"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON).headers(headers));
        f.server().expect(once(), requestTo(BASE + "/api/v1/activity/i2"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        IntervalsRateLimit withHeaders = f.client().getActivity("i1").rateLimit();
        IntervalsRateLimit without = f.client().getActivity("i2").rateLimit();

        assertThat(withHeaders.limit()).isEqualTo(2500);
        assertThat(withHeaders.remaining()).isEqualTo(2487);
        assertThat(withHeaders.toString()).isEqualTo("remaining=2487/2500");
        assertThat(without.limit()).isNull();
        assertThat(without.remaining()).isNull();
        assertThat(without.toString()).isEqualTo("remaining=-/-");
        f.server().verify();
    }

    // ---- failures -----------------------------------------------------------------------------------

    @Test
    void withoutAnApiKeyNoRequestIsSentAtAll() {
        Fixture f = client("");
        f.server().expect(never(), requestTo(BASE + "/api/v1/activity/i1"));

        assertThatThrownBy(() -> f.client().getActivity("i1"))
                .isInstanceOfSatisfying(IntervalsException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.NOT_CONFIGURED));

        f.server().verify();
    }

    @ParameterizedTest
    @CsvSource({
            "401, AUTH_FAILED",
            "403, FORBIDDEN",
            "429, RATE_LIMITED",
            "404, CLIENT_ERROR",
            "500, UPSTREAM_ERROR",
            "503, UPSTREAM_ERROR",
    })
    void httpStatusesAreClassifiedAndTheRequestIsSentExactlyOnce(int status, Reason reason) {
        Fixture f = client();
        f.server().expect(once(), requestTo(BASE + "/api/v1/activity/i1"))
                .andRespond(withStatus(org.springframework.http.HttpStatusCode.valueOf(status)));

        assertThatThrownBy(() -> f.client().getActivity("i1"))
                .isInstanceOfSatisfying(IntervalsException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(reason);
                    assertThat(e.getHttpStatus()).isEqualTo(status);
                });

        f.server().verify();   // exactly one request: a 429 is never retried or waited out
    }

    @Test
    void aTimeoutAndARefusedConnectionAreDistinguished() {
        Fixture timeoutFixture = client();
        timeoutFixture.server().expect(once(), requestTo(BASE + "/api/v1/activity/i1"))
                .andRespond(withException(new SocketTimeoutException("timed out")));
        assertThatThrownBy(() -> timeoutFixture.client().getActivity("i1"))
                .isInstanceOfSatisfying(IntervalsException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.TIMEOUT));

        Fixture refusedFixture = client();
        refusedFixture.server().expect(once(), requestTo(BASE + "/api/v1/activity/i1"))
                .andRespond(withException(new ConnectException("refused")));
        assertThatThrownBy(() -> refusedFixture.client().getActivity("i1"))
                .isInstanceOfSatisfying(IntervalsException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.CONNECTION_FAILED));
    }

    @Test
    void aBodyThatIsNotAJsonContainerIsAnInvalidResponse() {
        Fixture f = client();
        f.server().expect(once(), requestTo(BASE + "/api/v1/activity/i1"))
                .andRespond(withSuccess("42", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> f.client().getActivity("i1"))
                .isInstanceOfSatisfying(IntervalsException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.INVALID_RESPONSE));
    }

    @Test
    void aJsonNullBodyIsAcceptedAsAnEmptyAnswer() {
        Fixture f = client();
        f.server().expect(once(), requestTo(BASE + "/api/v1/athlete/0/wellness?oldest=2026-09-28&newest=2026-09-28"))
                .andRespond(withSuccess("null", MediaType.APPLICATION_JSON));

        IntervalsReadResponse response = f.client()
                .getWellness(LocalDate.parse("2026-09-28"), LocalDate.parse("2026-09-28"));

        assertThat(response.body().isNull()).isTrue();
        assertThat(response.size()).isZero();
    }

    @Test
    void anUnusableRangeOrIdIsRefusedBeforeAnyRequest() {
        Fixture f = client();
        LocalDate day = LocalDate.parse("2026-09-28");

        assertThatThrownBy(() -> f.client().listActivities(day.plusDays(1), day))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> f.client().listActivities(null, day))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> f.client().getWellness(day, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> f.client().getActivity("  "))
                .isInstanceOf(IllegalArgumentException.class);

        f.server().verify();
    }

    @Test
    void theApiKeyNeverAppearsInAnErrorMessage() {
        Fixture f = client("super-secret-key");
        f.server().expect(once(), requestTo(BASE + "/api/v1/activity/i1"))
                .andRespond(withStatus(org.springframework.http.HttpStatusCode.valueOf(401)));

        assertThatThrownBy(() -> f.client().getActivity("i1"))
                .isInstanceOf(IntervalsException.class)
                .satisfies(e -> {
                    StringBuilder text = new StringBuilder();
                    for (Throwable t = e; t != null; t = t.getCause()) {
                        text.append(t.getMessage()).append('\n');
                    }
                    assertThat(text.toString()).doesNotContain("super-secret-key");
                });
    }
}
