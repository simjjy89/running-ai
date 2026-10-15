package com.runningai.common.exception;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the framework-level error contract that no controller ever sees.
 * <p>
 * Before Phase 8B a known path called with the wrong verb, and a missing required request
 * parameter, both fell through to the catch-all handler and were answered 500 with a full stack
 * trace in the log. The 500s were wrong, and the stack traces diluted the real error signal the
 * watchdog reads. These tests fix both the corrected statuses and - just as importantly - the
 * 404 behaviour that must NOT change with them: an unknown path stays 404, a known path with an
 * unsupported verb is 405, and the two never merge.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GlobalExceptionHandlerApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unsupportedMethodOnAPostOnlyEndpointIsMethodNotAllowed() throws Exception {
        mockMvc.perform(get("/api/v1/garmin/sync"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(HttpHeaders.ALLOW, "POST"))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void unsupportedMethodOnAGetOnlyEndpointIsMethodNotAllowed() throws Exception {
        mockMvc.perform(delete("/api/v1/activities/1"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    /** The Allow header must list what does work, not merely signal that something else does. */
    @Test
    void methodNotAllowedAdvertisesTheSupportedMethods() throws Exception {
        mockMvc.perform(delete("/api/v1/activities/1"))
                .andExpect(header().exists(HttpHeaders.ALLOW))
                .andExpect(header().string(HttpHeaders.ALLOW, org.hamcrest.Matchers.containsString("GET")));
    }

    @Test
    void missingRequiredRequestParameterIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/intervals/fitness"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("oldest")));
    }

    /** Regression guard: correcting 405 must not turn unknown paths into 405 as well. */
    @Test
    void unknownPathStaysNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/no-such-path-xyz"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /** Regression guard: a domain 404 raised inside a controller keeps its own code. */
    @Test
    void missingResourceStaysDomainNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/activities/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACTIVITY_NOT_FOUND"));
    }

    /** Regression guard: a path variable of the wrong type is still a parse failure, not a 405. */
    @Test
    void malformedPathVariableStaysBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/activities/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void errorBodyIsTheExistingJsonContract() throws Exception {
        mockMvc.perform(get("/api/v1/garmin/sync"))
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.errors").doesNotExist());
    }
}
