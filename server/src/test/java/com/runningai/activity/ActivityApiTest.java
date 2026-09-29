package com.runningai.activity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end API tests over MockMvc against the real service, repository and
 * in-memory database. Each test runs in a rolled-back transaction, so tests do
 * not see each other's activities.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ActivityApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ActivityRepository activityRepository;

    private static Map<String, Object> validRequest(String externalId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("externalSource", "GARMIN");
        body.put("externalId", externalId);
        body.put("activityType", "RUN");
        body.put("startedAt", "2026-09-29T06:30:00+09:00");
        body.put("durationSeconds", 3600);
        body.put("distanceMeters", 10000);
        body.put("averageHeartRate", 155);
        body.put("maxHeartRate", 172);
        return body;
    }

    private MvcResult postActivity(Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/api/v1/activities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    @Test
    void createsActivityAndReturnsLocation() throws Exception {
        mockMvc.perform(post("/api/v1/activities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest("188081596"))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/api/v1/activities/")))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.athleteId").isNumber())
                .andExpect(jsonPath("$.externalSource").value("GARMIN"))
                .andExpect(jsonPath("$.externalId").value("188081596"))
                .andExpect(jsonPath("$.activityType").value("RUN"))
                // +09:00 input is normalised to UTC on storage
                .andExpect(jsonPath("$.startedAt").value("2026-09-28T21:30:00Z"))
                .andExpect(jsonPath("$.durationSeconds").value(3600))
                .andExpect(jsonPath("$.distanceMeters").value(10000.0))
                .andExpect(jsonPath("$.averageHeartRate").value(155))
                .andExpect(jsonPath("$.maxHeartRate").value(172))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists());

        assertThat(activityRepository.existsByExternalSourceAndExternalId(ExternalSource.GARMIN, "188081596")).isTrue();
    }

    @Test
    void getsActivityById() throws Exception {
        MvcResult created = postActivity(validRequest("get-1"));
        ActivityResponse response = objectMapper.readValue(created.getResponse().getContentAsString(), ActivityResponse.class);

        mockMvc.perform(get("/api/v1/activities/{id}", response.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(response.id()))
                .andExpect(jsonPath("$.externalId").value("get-1"));
    }

    @Test
    void returnsNotFoundForUnknownActivity() throws Exception {
        mockMvc.perform(get("/api/v1/activities/{id}", 999_999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACTIVITY_NOT_FOUND"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void listsActivitiesNewestFirst() throws Exception {
        Map<String, Object> older = validRequest("list-older");
        older.put("startedAt", "2026-09-27T06:30:00+09:00");
        Map<String, Object> newer = validRequest("list-newer");
        newer.put("startedAt", "2026-09-29T06:30:00+09:00");
        postActivity(older);
        postActivity(newer);

        mockMvc.perform(get("/api/v1/activities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].externalId").value("list-newer"))
                .andExpect(jsonPath("$[1].externalId").value("list-older"));
    }

    @Test
    void rejectsDuplicateExternalActivity() throws Exception {
        postActivity(validRequest("dup-1"));

        mockMvc.perform(post("/api/v1/activities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest("dup-1"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_ACTIVITY"));

        assertThat(activityRepository.count()).isEqualTo(1);
    }

    @Test
    void allowsSameExternalIdFromDifferentSource() throws Exception {
        postActivity(validRequest("shared-id"));
        Map<String, Object> fromIntervals = validRequest("shared-id");
        fromIntervals.put("externalSource", "INTERVALS_ICU");

        mockMvc.perform(post("/api/v1/activities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(fromIntervals)))
                .andExpect(status().isCreated());

        assertThat(activityRepository.count()).isEqualTo(2);
    }

    @Test
    void rejectsInvalidRequest() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("externalId", "");          // blank
        body.put("durationSeconds", -1);     // negative
        body.put("distanceMeters", -5);      // negative
        body.put("averageHeartRate", -1);    // negative
        // externalSource, activityType, startedAt missing

        mockMvc.perform(post("/api/v1/activities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[?(@.field == 'externalSource')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'externalId')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'activityType')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'startedAt')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'durationSeconds')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'distanceMeters')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'averageHeartRate')]").exists());

        assertThat(activityRepository.count()).isZero();
    }

    @Test
    void rejectsUnknownEnumValue() throws Exception {
        Map<String, Object> body = validRequest("bad-enum");
        body.put("activityType", "SWIM");

        mockMvc.perform(post("/api/v1/activities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
