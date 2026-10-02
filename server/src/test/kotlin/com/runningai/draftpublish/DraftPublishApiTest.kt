package com.runningai.draftpublish

import com.fasterxml.jackson.databind.ObjectMapper
import com.runningai.coach.CoachTestFixtures.draft
import com.runningai.coach.CoachTestFixtures.restDraft
import com.runningai.coach.CoachTestFixtures.segment
import com.runningai.coach.WorkoutDraft
import com.runningai.coach.WorkoutDraftApprovalRepository
import com.runningai.coach.WorkoutDraftRepository
import com.runningai.coach.WorkoutDraftStatus
import com.runningai.coach.WorkoutDraftStore
import com.runningai.integration.intervals.IntervalsException
import com.runningai.integration.intervals.IntervalsPublishOperation
import com.runningai.integration.intervals.IntervalsPublishResult
import com.runningai.integration.intervals.IntervalsWorkoutClient
import com.runningai.integration.intervals.IntervalsWorkoutPublisher
import com.runningai.integration.intervals.IntervalsWorkoutRenderer
import com.runningai.integration.intervals.RenderedIntervalsWorkout
import com.runningai.integration.intervals.WorkoutPublishApplicationService
import com.runningai.training.IntensityClass
import com.runningai.training.SegmentType
import com.runningai.training.StructuredWorkoutMapper
import com.runningai.training.WorkoutIntensityTargetService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Approve -> preview -> publish over HTTP with the draft-publishing switch ON.
 *
 * **No real external write is possible here**: [IntervalsWorkoutPublisher] and [IntervalsWorkoutClient]
 * are Mockito mocks (and the Intervals base URL points at a closed local port). The real renderer runs
 * (as a spy, so calls can be counted). The legacy deterministic path — [WorkoutPublishApplicationService],
 * [WorkoutIntensityTargetService], [StructuredWorkoutMapper] — is mocked and asserted untouched after
 * every test: an approved draft is published as approved, never re-prescribed.
 */
@SpringBootTest(
    properties = [
        "running-ai.draft-publishing.enabled=true",
        "running-ai.intervals.api-key=",
        "running-ai.intervals.base-url=http://127.0.0.1:9",
    ],
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DraftPublishApiTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var store: WorkoutDraftStore
    @Autowired private lateinit var drafts: WorkoutDraftRepository
    @Autowired private lateinit var approvals: WorkoutDraftApprovalRepository
    @Autowired private lateinit var publications: WorkoutDraftPublicationRepository
    @Autowired private lateinit var service: ApprovedWorkoutDraftPublishService
    @Autowired private lateinit var jdbc: JdbcTemplate

    @MockitoBean private lateinit var publisher: IntervalsWorkoutPublisher
    @MockitoBean private lateinit var intervalsClient: IntervalsWorkoutClient
    @MockitoBean private lateinit var legacyPublishService: WorkoutPublishApplicationService
    @MockitoBean private lateinit var targetService: WorkoutIntensityTargetService
    @MockitoBean private lateinit var legacyMapper: StructuredWorkoutMapper
    @MockitoSpyBean private lateinit var renderer: IntervalsWorkoutRenderer

    @AfterEach
    fun cleanUp() {
        // The legacy deterministic path and the raw client are never used by the draft path.
        verifyNoInteractions(legacyPublishService, targetService, legacyMapper, intervalsClient)
        publications.deleteAll()
        approvals.deleteAll()
        drafts.deleteAll()
    }

    // ---- helpers --------------------------------------------------------------------------------

    private fun saved(d: WorkoutDraft = draft()): Long =
        requireNotNull(store.save(d, UUID.randomUUID().toString(), supersede = null).id)

    private fun approve(id: Long): ResultActions = mockMvc.perform(post("/api/v1/workout-drafts/$id/approve"))
    private fun preview(id: Long): ResultActions = mockMvc.perform(get("/api/v1/workout-drafts/$id/publish-preview"))
    private fun publish(id: Long): ResultActions = mockMvc.perform(post("/api/v1/workout-drafts/$id/publish"))

    private fun approved(d: WorkoutDraft = draft()): Long = saved(d).also { approve(it).andExpect(status().isOk) }

    private fun publisherReturns(operation: IntervalsPublishOperation, eventId: String = "evt-1") {
        // doAnswer, not `when`: a stub set to throw would fire while being re-stubbed.
        doAnswer { IntervalsPublishResult(operation, eventId, true, it.getArgument(0)) }
            .`when`(publisher).publish(any(), any())
    }

    private val easyText = "- Warm Up 10m\n- Main 25m\n- Cool Down 5m"

    private val heartRateDraft = draft(
        segments = listOf(segment(SegmentType.MAIN, 40, IntensityClass.EASY, hrMin = 140, hrMax = 150)),
    )

    // ---- approval -------------------------------------------------------------------------------

    @Test
    fun `approve returns the approved draft identity and never touches any publisher`() {
        val id = saved()

        approve(id)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.draftId").value(id))
            .andExpect(jsonPath("$.draftGroupId").isNotEmpty)
            .andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.date").value("2026-10-02"))
            .andExpect(jsonPath("$.workoutType").value("EASY"))
            .andExpect(jsonPath("$.status").value("APPROVED"))
            .andExpect(jsonPath("$.approvalId").isNumber)
            .andExpect(jsonPath("$.approvedAt").isNotEmpty)

        verifyNoInteractions(publisher, intervalsClient, renderer)
    }

    @Test
    fun `approving twice returns the same approval`() {
        val id = saved()
        val first = objectMapper.readTree(approve(id).andReturn().response.contentAsString)

        approve(id)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.approvalId").value(first.get("approvalId").asLong()))
            .andExpect(jsonPath("$.approvedAt").value(first.get("approvedAt").asText()))
        assertThat(approvals.count()).isEqualTo(1)
    }

    @Test
    fun `a second draft for an already approved date is a 409`() {
        approved(draft(title = "A"))
        val b = saved(draft(title = "B"))

        approve(b)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("WORKOUT_DATE_ALREADY_APPROVED"))
    }

    @Test
    fun `a superseded draft cannot be approved`() {
        val group = UUID.randomUUID().toString()
        val v1 = store.save(draft(version = 1), group, supersede = null)
        store.save(draft(version = 2), group, supersede = v1.id)

        approve(v1.id!!)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("WORKOUT_DRAFT_SUPERSEDED"))
    }

    @Test
    fun `approving an unknown draft is a 404`() {
        approve(987_654_321L)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("WORKOUT_DRAFT_NOT_FOUND"))
    }

    @Test
    fun `revising an approved draft is refused before the coach is asked`() {
        val id = approved()

        mockMvc.perform(
            post("/api/v1/workout-drafts/$id/revisions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"request":"make it longer"}"""),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("WORKOUT_DRAFT_APPROVED_IMMUTABLE"))

        assertThat(drafts.findById(id).get().status).isEqualTo(WorkoutDraftStatus.APPROVED)
        assertThat(drafts.count()).isEqualTo(1)
    }

    // ---- not approved -> no preview, no publish -------------------------------------------------

    @Test
    fun `a draft that is not approved can be neither previewed nor published`() {
        val id = saved()

        preview(id).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("WORKOUT_DRAFT_APPROVAL_REQUIRED"))
        publish(id).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("WORKOUT_DRAFT_APPROVAL_REQUIRED"))

        verifyNoInteractions(publisher, renderer)
        assertThat(publications.count()).isZero()
    }

    @Test
    fun `a superseded draft can be neither previewed nor published`() {
        val group = UUID.randomUUID().toString()
        val v1 = store.save(draft(version = 1), group, supersede = null)
        store.save(draft(version = 2), group, supersede = v1.id)

        preview(v1.id!!).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("WORKOUT_DRAFT_SUPERSEDED"))
        publish(v1.id!!).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("WORKOUT_DRAFT_SUPERSEDED"))

        verifyNoInteractions(publisher, renderer)
    }

    @Test
    fun `publishing an unknown draft is a 404`() {
        publish(987_654_321L).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("WORKOUT_DRAFT_NOT_FOUND"))
    }

    // ---- REST -----------------------------------------------------------------------------------

    @Test
    fun `an approved rest day previews as an explicit skip with no external write`() {
        val id = approved(restDraft())

        preview(id)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.restDay").value(true))
            .andExpect(jsonPath("$.publishable").value(true))
            .andExpect(jsonPath("$.externalWriteRequired").value(false))
            .andExpect(jsonPath("$.expectedOutcome").value("SKIPPED_REST_DAY"))
            .andExpect(jsonPath("$.renderedWorkoutText").value(DraftPublishPreviewResponse.REST_DAY_REPRESENTATION))
            .andExpect(jsonPath("$.structuredStepCount").value(0))
            .andExpect(jsonPath("$.publication").value(nullValue()))

        verifyNoInteractions(publisher, intervalsClient, renderer)
    }

    @Test
    fun `publishing an approved rest day is SKIPPED_REST_DAY with zero renderer, publisher and client calls`() {
        val id = approved(restDraft())

        publish(id)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.outcome").value("SKIPPED_REST_DAY"))
            .andExpect(jsonPath("$.intervalsOperation").value(nullValue()))
            .andExpect(jsonPath("$.remoteEventId").value(nullValue()))
            .andExpect(jsonPath("$.verified").value(nullValue()))
            .andExpect(jsonPath("$.alreadyPublished").value(false))

        verifyNoInteractions(publisher, intervalsClient, renderer)
        val row = publications.findByDraftId(id)!!
        assertThat(row.outcome).isEqualTo(DraftPublicationOutcome.SKIPPED_REST_DAY)
        assertThat(row.intervalsOperation).isNull()
        assertThat(row.remoteEventId).isNull()
    }

    @Test
    fun `publishing the same rest day again returns the stored skip`() {
        val id = approved(restDraft())
        publish(id).andExpect(status().isOk)

        publish(id)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.outcome").value("SKIPPED_REST_DAY"))
            .andExpect(jsonPath("$.alreadyPublished").value(true))

        assertThat(publications.count()).isEqualTo(1)
        verifyNoInteractions(publisher, intervalsClient, renderer)
    }

    // ---- normal workout -------------------------------------------------------------------------

    @Test
    fun `an approved workout previews the exact rendered text without publishing`() {
        val id = approved()

        preview(id)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.restDay").value(false))
            .andExpect(jsonPath("$.publishable").value(true))
            .andExpect(jsonPath("$.externalWriteRequired").value(true))
            .andExpect(jsonPath("$.expectedOutcome").value("PUBLISH"))
            .andExpect(jsonPath("$.renderedWorkoutText").value(easyText))
            .andExpect(jsonPath("$.structuredStepCount").value(3))
            .andExpect(jsonPath("$.unpublishableReasons").isEmpty)

        verifyNoInteractions(publisher)
        assertThat(publications.count()).isZero()
    }

    @Test
    fun `publishing an approved workout calls the existing publisher exactly once with the approved text`() {
        val id = approved()
        publisherReturns(IntervalsPublishOperation.CREATED, "evt-42")

        publish(id)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.draftId").value(id))
            .andExpect(jsonPath("$.outcome").value("PUBLISHED"))
            .andExpect(jsonPath("$.intervalsOperation").value("CREATED"))
            .andExpect(jsonPath("$.remoteEventId").value("evt-42"))
            .andExpect(jsonPath("$.verified").value(true))
            .andExpect(jsonPath("$.structuredStepCount").value(3))
            .andExpect(jsonPath("$.alreadyPublished").value(false))

        verify(publisher, times(1)).publish(LocalDate.of(2026, 10, 2), RenderedIntervalsWorkout(easyText))
        val row = publications.findByDraftId(id)!!
        assertThat(row.outcome).isEqualTo(DraftPublicationOutcome.PUBLISHED)
        assertThat(row.intervalsOperation).isEqualTo(IntervalsPublishOperation.CREATED)
        assertThat(row.remoteEventId).isEqualTo("evt-42")
        assertThat(row.verified).isTrue()
        assertThat(row.approvalId).isEqualTo(approvals.findByDraftId(id)!!.id)
    }

    @Test
    fun `CREATED, UPDATED and NO_CHANGE are each stored as reported by the publisher`() {
        IntervalsPublishOperation.entries.forEachIndexed { i, operation ->
            val id = approved(draft(date = LocalDate.of(2026, 10, 10 + i)))
            publisherReturns(operation, "evt-$i")

            publish(id).andExpect(status().isOk).andExpect(jsonPath("$.intervalsOperation").value(operation.name))

            val row = publications.findByDraftId(id)!!
            assertThat(row.intervalsOperation).isEqualTo(operation)
            assertThat(row.remoteEventId).isEqualTo("evt-$i")
        }
        assertThat(publications.count()).isEqualTo(3)
    }

    @Test
    fun `publishing again returns the stored result and never calls the publisher a second time`() {
        val id = approved()
        publisherReturns(IntervalsPublishOperation.CREATED, "evt-7")
        publish(id).andExpect(status().isOk)

        publish(id)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.intervalsOperation").value("CREATED"))
            .andExpect(jsonPath("$.remoteEventId").value("evt-7"))
            .andExpect(jsonPath("$.alreadyPublished").value(true))
            .andExpect(jsonPath("$.structuredStepCount").value(nullValue()))

        verify(publisher, times(1)).publish(any(), any())
        assertThat(publications.count()).isEqualTo(1)
    }

    @Test
    fun `a failed publish stores nothing, keeps the draft approved and can be retried`() {
        val id = approved()
        `when`(publisher.publish(any(), any()))
            .thenThrow(IntervalsException(IntervalsException.Reason.TIMEOUT, null, "Intervals.icu timed out"))

        publish(id)
            .andExpect(status().isGatewayTimeout)
            .andExpect(jsonPath("$.code").value("INTERVALS_TIMEOUT"))

        assertThat(publications.count()).isZero()
        assertThat(drafts.findById(id).get().status).isEqualTo(WorkoutDraftStatus.APPROVED)

        // explicit retry: the publisher's own marker lookup decides CREATED vs NO_CHANGE remotely
        publisherReturns(IntervalsPublishOperation.NO_CHANGE, "evt-9")
        publish(id)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.intervalsOperation").value("NO_CHANGE"))
            .andExpect(jsonPath("$.alreadyPublished").value(false))

        verify(publisher, times(2)).publish(any(), any())
        assertThat(publications.findByDraftId(id)!!.remoteEventId).isEqualTo("evt-9")
    }

    @Test
    fun `a publisher conflict is reported as such and nothing is stored`() {
        val id = approved()
        `when`(publisher.publish(any(), any())).thenThrow(
            IntervalsException(IntervalsException.Reason.UNMANAGED_WORKOUT_CONFLICT, null, "foreign workout on date"),
        )

        publish(id).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("INTERVALS_UNMANAGED_WORKOUT_CONFLICT"))

        assertThat(publications.count()).isZero()
    }

    // ---- fail closed ----------------------------------------------------------------------------

    @Test
    fun `an approved draft with a bpm heart-rate target previews as unpublishable and is never sent`() {
        val id = approved(heartRateDraft)

        preview(id)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.publishable").value(false))
            .andExpect(jsonPath("$.expectedOutcome").value("UNPUBLISHABLE"))
            .andExpect(jsonPath("$.externalWriteRequired").value(false))
            .andExpect(jsonPath("$.renderedWorkoutText").value(nullValue()))
            .andExpect(jsonPath("$.unpublishableReasons[0]").value(org.hamcrest.Matchers.containsString("heart-rate")))

        publish(id)
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.code").value("UNPUBLISHABLE_DRAFT"))
            .andExpect(jsonPath("$.errors[0].message").value(org.hamcrest.Matchers.containsString("heart-rate")))

        verifyNoInteractions(publisher, intervalsClient)
        assertThat(publications.count()).isZero()
        assertThat(drafts.findById(id).get().status).isEqualTo(WorkoutDraftStatus.APPROVED)
    }

    @Test
    fun `an approved workout of an unmapped type fails closed before the publisher`() {
        val id = approved(draft(workoutType = "CROSS_TRAINING"))

        publish(id).andExpect(status().isUnprocessableEntity).andExpect(jsonPath("$.code").value("UNPUBLISHABLE_DRAFT"))

        verifyNoInteractions(publisher, intervalsClient)
    }

    @Test
    fun `an approved interval session is published as the expanded approved structure`() {
        val id = approved(
            draft(
                workoutType = "INTERVAL",
                segments = listOf(
                    segment(SegmentType.WARM_UP, 10, IntensityClass.VERY_EASY),
                    segment(SegmentType.MAIN, 3, IntensityClass.HARD, paceFast = 270, paceSlow = 285,
                        repetitions = 2, recoveryMinutes = 2),
                    segment(SegmentType.COOL_DOWN, 10, IntensityClass.VERY_EASY),
                ),
            ),
        )
        publisherReturns(IntervalsPublishOperation.CREATED)

        publish(id).andExpect(status().isOk).andExpect(jsonPath("$.structuredStepCount").value(6))

        val text = "- Warm Up 10m\n- Main 3m 4:30-4:45/km Pace\n- Rest 2m\n- Main 3m 4:30-4:45/km Pace\n- Rest 2m\n- Cool Down 10m"
        verify(publisher).publish(LocalDate.of(2026, 10, 2), RenderedIntervalsWorkout(text))
    }

    // ---- concurrency ----------------------------------------------------------------------------

    @Test
    fun `a concurrent publish of the same draft is refused and the publisher runs once`() {
        val id = approved()
        val other = approved(restDraft(date = LocalDate.of(2026, 10, 20)))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        `when`(publisher.publish(any(), any())).thenAnswer {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "test latch timed out" }
            IntervalsPublishResult(IntervalsPublishOperation.CREATED, "evt-c", true, it.getArgument(0))
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val first = executor.submit<DraftPublishOutcome> { service.publish(id) }
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue()

            assertThatThrownBy { service.publish(id) }.isInstanceOf(DraftPublishAlreadyRunningException::class.java)
            mockMvc.perform(post("/api/v1/workout-drafts/$id/publish"))
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("DRAFT_PUBLISH_ALREADY_RUNNING"))
            // a different draft is not blocked
            assertThat(service.publish(other).publication.outcome).isEqualTo(DraftPublicationOutcome.SKIPPED_REST_DAY)

            release.countDown()
            assertThat(first.get(10, TimeUnit.SECONDS).publication.intervalsOperation)
                .isEqualTo(IntervalsPublishOperation.CREATED)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }

        verify(publisher, times(1)).publish(any(), any())
        assertThat(publications.count()).isEqualTo(2)
        // the guard is released afterwards: a later request gets the stored result
        assertThat(service.publish(id).alreadyPublished).isTrue()
        verify(publisher, times(1)).publish(any(), any())
    }

    // ---- database-level guarantee ---------------------------------------------------------------

    @Test
    fun `the database refuses a second publication of one draft and malformed outcomes`() {
        val id = approved()
        val approvalId = approvals.findByDraftId(id)!!.id
        val insert = "insert into workout_draft_publication " +
            "(draft_id, approval_id, outcome, intervals_operation, remote_event_id, verified, published_at) " +
            "values (?, ?, ?, ?, ?, ?, current_timestamp)"
        jdbc.update(insert, id, approvalId, "PUBLISHED", "CREATED", "evt-1", true)

        assertThatThrownBy { jdbc.update(insert, id, approvalId, "PUBLISHED", "UPDATED", "evt-1", true) }
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException::class.java)

        val restId = approved(restDraft(date = LocalDate.of(2026, 10, 21)))
        val restApproval = approvals.findByDraftId(restId)!!.id
        assertThatThrownBy { jdbc.update(insert, restId, restApproval, "SKIPPED_REST_DAY", null, "evt-x", null) }
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException::class.java)
        assertThatThrownBy { jdbc.update(insert, restId, restApproval, "PUBLISHED", null, null, null) }
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException::class.java)
        verify(publisher, never()).publish(any(), any())
    }
}
