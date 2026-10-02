package com.runningai.integration.intervals;

import com.runningai.integration.intervals.IntervalsException.Reason;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * The pieces every Intervals.icu call shares: Basic authorization, one-shot execution and the mapping
 * from a transport failure to an {@link IntervalsException}. Extracted in Phase 6H-5 so the read-only
 * enrichment client and the workout publisher cannot drift apart on authentication or error semantics.
 * <p>
 * Nothing here retries. A 429 is reported as {@link Reason#RATE_LIMITED} and the caller stops; waiting
 * out a {@code Retry-After} automatically is deliberately not implemented.
 * <p>
 * Messages carry the status code only - never the API key, the Authorization header or a response body.
 */
final class IntervalsHttp {

    private IntervalsHttp() {
    }

    /**
     * Intervals.icu personal-API-key auth: user name {@code API_KEY}, the key as the password.
     *
     * @throws IntervalsException {@link Reason#NOT_CONFIGURED} when no key is configured, so an
     *                            unauthenticated request is never sent
     */
    static String authorization(IntervalsProperties properties) {
        if (!properties.hasApiKey()) {
            throw new IntervalsException(Reason.NOT_CONFIGURED, null,
                    "Intervals.icu API key is not configured (running-ai.intervals.api-key / INTERVALS_API_KEY)");
        }
        String credentials = "API_KEY:" + properties.apiKey();
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    /** Runs one call, translating transport failures. Never retries, never swallows. */
    static <T> T execute(Call<T> call) {
        try {
            return call.run();
        } catch (RestClientResponseException e) {
            throw toException(e);
        } catch (ResourceAccessException e) {
            boolean timeout = e.getCause() instanceof SocketTimeoutException
                    || (e.getMessage() != null && e.getMessage().toLowerCase().contains("timed out"));
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IntervalsException(timeout ? Reason.TIMEOUT : Reason.CONNECTION_FAILED, null,
                    "Intervals.icu " + (timeout ? "request timed out" : "not reachable")
                            + " (" + cause.getClass().getSimpleName() + ")", e);
        }
    }

    static IntervalsException toException(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        Reason reason;
        if (status == 401) {
            reason = Reason.AUTH_FAILED;
        } else if (status == 403) {
            reason = Reason.FORBIDDEN;
        } else if (status == 429) {
            reason = Reason.RATE_LIMITED;
        } else if (status >= 500) {
            reason = Reason.UPSTREAM_ERROR;
        } else {
            reason = Reason.CLIENT_ERROR;
        }
        return new IntervalsException(reason, status, "Intervals.icu responded " + status, e);
    }

    /**
     * What the response says about the caller's remaining budget, for logging and for deciding by hand
     * whether to keep going. Intervals.icu documents 5000 requests/day, 2500 per rolling 15 minutes and
     * 10 requests/second per IP; none of these values is acted on automatically.
     */
    static IntervalsRateLimit rateLimitOf(HttpHeaders headers) {
        if (headers == null) {
            return IntervalsRateLimit.UNKNOWN;
        }
        return new IntervalsRateLimit(
                integerHeader(headers, "X-RateLimit-Limit"),
                integerHeader(headers, "X-RateLimit-Remaining"),
                headers.getFirst("Retry-After"));
    }

    private static Integer integerHeader(HttpHeaders headers, String name) {
        String value = headers.getFirst(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @FunctionalInterface
    interface Call<T> {
        T run();
    }
}
