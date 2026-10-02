package com.runningai.draftpublish

import com.runningai.coach.CoachTestFixtures.draft
import com.runningai.coach.CoachTestFixtures.restDraft
import com.runningai.coach.WorkoutDraft
import com.runningai.coach.WorkoutDraftApprovalRepository
import com.runningai.coach.WorkoutDraftRepository
import com.runningai.coach.WorkoutDraftStore
import com.runningai.integration.intervals.IntervalsWorkoutClient
import com.runningai.integration.intervals.IntervalsWorkoutPublisher
import com.runningai.integration.intervals.WorkoutPublishApplicationService
import com.runningai.integration.intervals.WorkoutPublishProperties
import com.runningai.integration.intervals.WorkoutPublishingScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.util.UUID

/**
 * Default configuration: the draft-publishing switch is OFF. Approval and preview still work; the
 * publish action is refused for every draft, a REST day included, and nothing reaches a publisher.
 */
@SpringBootTest(properties = ["running-ai.intervals.api-key=", "running-ai.intervals.base-url=http://127.0.0.1:9"])
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DraftPublishSwitchOffApiTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var store: WorkoutDraftStore
    @Autowired private lateinit var drafts: WorkoutDraftRepository
    @Autowired private lateinit var approvals: WorkoutDraftApprovalRepository
    @Autowired private lateinit var publications: WorkoutDraftPublicationRepository
    @Autowired private lateinit var properties: DraftPublishingProperties
    @Autowired private lateinit var legacyProperties: WorkoutPublishProperties
    @Autowired private lateinit var context: ApplicationContext

    @MockitoBean private lateinit var publisher: IntervalsWorkoutPublisher
    @MockitoBean private lateinit var intervalsClient: IntervalsWorkoutClient
    @MockitoBean private lateinit var legacyPublishService: WorkoutPublishApplicationService

    @AfterEach
    fun cleanUp() {
        verifyNoInteractions(publisher, intervalsClient, legacyPublishService)
        publications.deleteAll()
        approvals.deleteAll()
        drafts.deleteAll()
    }

    private fun approved(d: WorkoutDraft): Long {
        val id = requireNotNull(store.save(d, UUID.randomUUID().toString(), supersede = null).id)
        mockMvc.perform(post("/api/v1/workout-drafts/$id/approve")).andExpect(status().isOk)
        return id
    }

    @Test
    fun `every publish switch defaults to off and no publish scheduler exists`() {
        assertThat(properties.enabled).isFalse()
        assertThat(legacyProperties.enabled()).isFalse()
        assertThat(legacyProperties.scheduler().enabled()).isFalse()
        assertThat(context.getBeansOfType(WorkoutPublishingScheduler::class.java)).isEmpty()
    }

    @Test
    fun `approve and preview work while publishing is off`() {
        val id = approved(draft())

        mockMvc.perform(get("/api/v1/workout-drafts/$id/publish-preview"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.publishable").value(true))
            .andExpect(jsonPath("$.renderedWorkoutText").value("- Warm Up 10m\n- Main 25m\n- Cool Down 5m"))
    }

    @Test
    fun `publishing an approved workout is refused while the switch is off`() {
        val id = approved(draft())

        mockMvc.perform(post("/api/v1/workout-drafts/$id/publish"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("DRAFT_PUBLISHING_DISABLED"))

        assertThat(publications.count()).isZero()
    }

    @Test
    fun `even an approved rest day is not recorded while the switch is off`() {
        val id = approved(restDraft(date = LocalDate.of(2026, 10, 5)))

        mockMvc.perform(get("/api/v1/workout-drafts/$id/publish-preview"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.expectedOutcome").value("SKIPPED_REST_DAY"))
        mockMvc.perform(post("/api/v1/workout-drafts/$id/publish"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("DRAFT_PUBLISHING_DISABLED"))

        assertThat(publications.count()).isZero()
    }
}
