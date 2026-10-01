package com.runningai.integration.intervals;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Intervals.icu connection settings. The API key comes from the environment only
 * ({@code INTERVALS_API_KEY}); it may be blank so the application still starts without it, and the
 * client then fails every call with {@code NOT_CONFIGURED} instead of sending an unauthenticated request.
 * <p>
 * {@code athleteId} {@code 0} is the Intervals.icu shortcut for "the athlete that owns the API key".
 */
@Validated
@ConfigurationProperties(prefix = "running-ai.intervals")
public record IntervalsProperties(
        @NotBlank @DefaultValue("https://intervals.icu") String baseUrl,
        @NotBlank @DefaultValue("0") String athleteId,
        @DefaultValue("") String apiKey,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("15s") Duration readTimeout
) {

    boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** Never prints the API key. */
    @Override
    public String toString() {
        return "IntervalsProperties[baseUrl=" + baseUrl + ", athleteId=" + athleteId
                + ", apiKey=" + (hasApiKey() ? "<set>" : "<not set>")
                + ", connectTimeout=" + connectTimeout + ", readTimeout=" + readTimeout + "]";
    }
}
