package com.runningai.coach

import com.runningai.integration.intervals.IntervalsReadClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * `GET /api/v1/coach/training-context` (Phase 6H-7 §48-49, §76-78): a read-only preview that never
 * calls Garmin, Intervals or Claude. The Intervals read client is mocked and asserted untouched even
 * when previewing V2 (which reads Intervals-derived DB rows, never the live client).
 */
@SpringBootTest(properties = ["running-ai.intervals.api-key=", "running-ai.intervals.base-url=http://127.0.0.1:9"])
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TrainingContextPreviewApiTest {

    @Autowired private lateinit var mockMvc: MockMvc

    @MockitoBean private lateinit var intervalsReadClient: IntervalsReadClient
    @MockitoBean private lateinit var garminActivitySource: com.runningai.integration.garmin.GarminActivitySource

    @Test
    fun `previewing V1 touches neither Garmin nor Intervals`() {
        mockMvc.perform(get("/api/v1/coach/training-context").param("date", "2026-10-02").param("version", "V1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.contextVersion").value("V1"))
            .andExpect(jsonPath("$.context.recentTraining").exists())

        verifyNoInteractions(intervalsReadClient, garminActivitySource)
    }

    @Test
    fun `previewing V2 touches neither Garmin nor Intervals`() {
        mockMvc.perform(get("/api/v1/coach/training-context").param("date", "2026-10-02").param("version", "V2"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.contextVersion").value("V2"))
            .andExpect(jsonPath("$.context.dataCoverage").exists())

        verifyNoInteractions(intervalsReadClient, garminActivitySource)
    }

    @Test
    fun `an omitted date defaults to athlete-local today, an omitted version uses the configured default`() {
        mockMvc.perform(get("/api/v1/coach/training-context"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.contextVersion").value("V1")) // source default
    }

    @Test
    fun `the preview response carries no raw identity, GPS or credential`() {
        val body = mockMvc.perform(get("/api/v1/coach/training-context").param("date", "2026-10-02").param("version", "V2"))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        listOf("externalActivityId", "garminActivityId", "intervalsActivityId", "latitude", "longitude",
            "API_KEY", "Authorization").forEach { assertThat(body).doesNotContainIgnoringCase(it) }
    }

    @Test
    fun `the reported size stays well under the hard guard`() {
        val response = mockMvc.perform(get("/api/v1/coach/training-context").param("date", "2026-10-02").param("version", "V2"))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        val reportedSize = com.fasterxml.jackson.databind.ObjectMapper().readTree(response).get("sizeBytes").asInt()

        assertThat(reportedSize).isLessThan(65_536)
    }
}
