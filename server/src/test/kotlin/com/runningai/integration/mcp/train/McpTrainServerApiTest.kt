package com.runningai.integration.mcp.train

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.runningai.coach.AiCoachException
import com.runningai.coach.ApprovedWorkoutDraft
import com.runningai.coach.CoachTestFixtures
import com.runningai.coach.SessionConstraints
import com.runningai.coach.TrainingEnvironment
import com.runningai.coach.WorkoutDateAlreadyApprovedException
import com.runningai.coach.WorkoutDraft
import com.runningai.coach.WorkoutDraftApproval
import com.runningai.coach.WorkoutDraftApprovalService
import com.runningai.coach.WorkoutDraftApprovedImmutableException
import com.runningai.coach.WorkoutDraftNotFoundException
import com.runningai.coach.WorkoutDraftService
import com.runningai.coach.WorkoutDraftStatus
import com.runningai.coach.WorkoutDraftSupersededException
import com.runningai.draftpublish.ApprovedWorkoutDraftPublishService
import com.runningai.draftpublish.DraftPublicationOutcome
import com.runningai.draftpublish.DraftPublishAlreadyRunningException
import com.runningai.draftpublish.DraftPublishOutcome
import com.runningai.draftpublish.DraftPublishPreview
import com.runningai.draftpublish.DraftPublishingDisabledException
import com.runningai.draftpublish.UnpublishableDraftException
import com.runningai.draftpublish.WorkoutDraftNotApprovedException
import com.runningai.draftpublish.WorkoutDraftPublication
import com.runningai.injury.InjurySafetyDecision
import com.runningai.injury.InjurySafetyReasonCode
import com.runningai.injury.WorkoutSafetyViolationException
import com.runningai.integration.intervals.IntervalsException
import com.runningai.integration.intervals.IntervalsPublishOperation
import com.runningai.integration.intervals.IntervalsWorkoutClient
import com.runningai.integration.intervals.IntervalsWorkoutPublisher
import com.runningai.integration.intervals.RenderedIntervalsWorkout
import com.runningai.integration.intervals.WorkoutPublishApplicationService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.time.Instant
import java.time.LocalDate

/**
 * The Phase 7F-1 training-lifecycle MCP tools end to end over the real Streamable HTTP endpoint,
 * with the three lifecycle services mocked so every branch - including the ones a real coach or a
 * real Intervals account could not be made to produce on demand - is exercised deterministically.
 *
 * What these tests actually pin down:
 *  - the six tools are registered and reach exactly the service the REST API reaches;
 *  - a domain refusal arrives at the caller as the SAME code the REST API returns, never as a
 *    success and never as a fabricated workout;
 *  - approving and publishing cannot happen as a side effect of a tool call: each needs the
 *    athlete's explicit confirmation AND an echo of something only a prior read can supply.
 *
 * Stubbing uses the `doReturn/doThrow(...).when(mock)` form throughout, not `when(mock.call())`:
 * the latter actually invokes the mock, which blows up as soon as a method is already stubbed to
 * throw - exactly the situation several of these tests set up on purpose.
 */
@SpringBootTest(
    properties = [
        "running-ai.mcp.train-enabled=true",
        "running-ai.garmin.connector.base-url=http://127.0.0.1:9",
        "running-ai.intervals.api-key=",
        "running-ai.intervals.base-url=http://127.0.0.1:9",
    ],
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class McpTrainServerApiTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper

    @MockitoBean private lateinit var draftService: WorkoutDraftService
    @MockitoBean private lateinit var approvalService: WorkoutDraftApprovalService
    @MockitoBean private lateinit var publishService: ApprovedWorkoutDraftPublishService

    // The transports themselves: asserted untouched: only the service a tool called may reach one.
    @MockitoBean private lateinit var legacyPublish: WorkoutPublishApplicationService
    @MockitoBean private lateinit var publisher: IntervalsWorkoutPublisher
    @MockitoBean private lateinit var intervalsClient: IntervalsWorkoutClient

    private val date: LocalDate = LocalDate.of(2026, 10, 20)

    // ---- plumbing -------------------------------------------------------------------------------

    private fun rpc(method: String, params: String?): JsonNode {
        val body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"$method\"" +
            (params?.let { ",\"params\":$it" } ?: "") + "}"
        val result = mockMvc.perform(
            post("/mcp").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .header("MCP-Protocol-Version", "2025-06-18")
                .content(body),
        ).andReturn()
        val response = result.response.contentAsString
        assertThat(result.response.status).`as`(response).isEqualTo(200)
        return objectMapper.readTree(response)
    }

    private fun call(tool: String, arguments: String = "{}"): JsonNode =
        rpc("tools/call", "{\"name\":\"$tool\",\"arguments\":$arguments}").path("result")

    private fun ok(tool: String, arguments: String = "{}"): JsonNode {
        val result = call(tool, arguments)
        assertThat(result.path("isError").asBoolean()).`as`(result.toString()).isFalse()
        val body = result.path("structuredContent")
        // Every answer carries its own id, so one call can be traced through the server log.
        assertThat(body.path("correlationId").asText()).hasSize(36)
        return body
    }

    private fun failure(tool: String, arguments: String = "{}"): JsonNode {
        val result = call(tool, arguments)
        assertThat(result.path("isError").asBoolean()).`as`(result.toString()).isTrue()
        val body = result.path("structuredContent")
        assertThat(body.path("success").asBoolean()).isFalse()
        assertThat(body.path("correlationId").asText()).hasSize(36)
        return body
    }

    private fun codeOf(tool: String, arguments: String = "{}"): String =
        failure(tool, arguments).path("code").asText()

    // ---- fixtures -------------------------------------------------------------------------------

    private fun draft(
        id: Long = 11L,
        version: Int = 1,
        status: WorkoutDraftStatus = WorkoutDraftStatus.DRAFT,
    ): WorkoutDraft = CoachTestFixtures.draft(date = date, version = version)
        .copy(id = id, draftGroupId = "group-1", status = status)

    private fun approved(id: Long = 11L, approvalId: Long = 77L): ApprovedWorkoutDraft =
        ApprovedWorkoutDraft(
            draft(id, status = WorkoutDraftStatus.APPROVED),
            WorkoutDraftApproval(approvalId, id, 1L, date, Instant.parse("2026-10-20T01:00:00Z")),
        )

    private fun publication(
        id: Long = 99L,
        draftId: Long = 11L,
        approvalId: Long = 77L,
        outcome: DraftPublicationOutcome = DraftPublicationOutcome.PUBLISHED,
    ): WorkoutDraftPublication {
        val sent = outcome == DraftPublicationOutcome.PUBLISHED
        return WorkoutDraftPublication(
            id, draftId, approvalId, outcome,
            if (sent) IntervalsPublishOperation.CREATED else null,
            if (sent) "evt-1" else null,
            if (sent) true else null,
            Instant.parse("2026-10-20T02:00:00Z"),
        )
    }

    private fun readyPreview(approvalId: Long = 77L) = DraftPublishPreview(
        approved(approvalId = approvalId), null,
        RenderedIntervalsWorkout("- 10m 65-75%\n- 25m 75-85%\n- 5m 65-75%"),
        emptyList(), null,
    )

    private fun safetyBlocked() = WorkoutSafetyViolationException(
        InjurySafetyDecision(
            nonRestTrainingAllowed = false,
            reasonCode = InjurySafetyReasonCode.INJURY_ACTIVE,
            message = "An active injury report allows REST only",
            latestReport = null,
            evaluatedAt = Instant.parse("2026-10-20T00:00:00Z"),
        ),
        "approval of workout draft 11",
    )

    // Mockito's matchers return null, and Kotlin inserts a runtime null check whenever a
    // platform-typed value is passed to a KOTLIN function with non-null parameters - which these
    // three services all are. Each matcher therefore gets a non-null fallback: by the time `?:`
    // runs the matcher is already registered on Mockito's stack, and the value handed to the mock
    // is never what matching is based on.
    private fun eqDate(v: LocalDate): LocalDate = eq(v) ?: v
    private fun eqLong(v: Long): Long = eq(v) ?: v
    private fun eqText(v: String): String = eq(v) ?: v
    private fun anyText(): String = anyString() ?: ""
    private fun anyConstraints(): SessionConstraints = any(SessionConstraints::class.java) ?: SessionConstraints()
    private fun capturedConstraints(c: ArgumentCaptor<SessionConstraints>): SessionConstraints =
        c.capture() ?: SessionConstraints()

    // ---- 1. registration and separation ----------------------------------------------------------

    @Test
    fun `all six lifecycle tools are registered, and the read and legacy write tools are not`() {
        val names = rpc("tools/list", "{}").path("result").path("tools").map { it.path("name").asText() }
        assertThat(names).containsExactlyInAnyOrder(
            "create_workout_draft", "get_workout_draft", "revise_workout_draft",
            "approve_workout_draft", "preview_draft_publish", "publish_approved_draft",
        )
    }

    @Test
    fun `only the two inspection tools are annotated read-only, and only publish opens the world`() {
        val tools = rpc("tools/list", "{}").path("result").path("tools")
            .associateBy({ it.path("name").asText() }, { it.path("annotations") })

        listOf("get_workout_draft", "preview_draft_publish").forEach {
            assertThat(tools.getValue(it).path("readOnlyHint").asBoolean()).`as`(it).isTrue()
        }
        listOf("create_workout_draft", "revise_workout_draft", "approve_workout_draft",
            "publish_approved_draft").forEach {
            assertThat(tools.getValue(it).path("readOnlyHint").asBoolean()).`as`(it).isFalse()
        }
        // Nothing in this lifecycle destroys anything; exactly one tool talks to an outside system.
        tools.forEach { (name, a) -> assertThat(a.path("destructiveHint").asBoolean()).`as`(name).isFalse() }
        assertThat(tools.getValue("publish_approved_draft").path("openWorldHint").asBoolean()).isTrue()
        assertThat(tools.getValue("create_workout_draft").path("openWorldHint").asBoolean()).isFalse()
    }

    // ---- 2. the happy path, step by step -----------------------------------------------------------

    @Test
    fun `create, read, revise, approve, preview and publish each reach the existing service`() {
        doReturn(date).`when`(draftService).today()
        doReturn(draft()).`when`(draftService).generate(eqDate(date), anyConstraints())
        val created = ok("create_workout_draft", "{\"date\":\"2026-10-20\"}")
        assertThat(created.path("draft").path("id").asLong()).isEqualTo(11L)
        assertThat(created.path("draft").path("status").asText()).isEqualTo("DRAFT")
        assertThat(created.path("draft").path("segments")).hasSize(3)

        doReturn(draft()).`when`(draftService).get(11L)
        assertThat(ok("get_workout_draft", "{\"draftId\":11}").path("draft").path("version").asInt()).isEqualTo(1)

        doReturn(draft(id = 12L, version = 2)).`when`(draftService)
            .revise(eqLong(11L), eqText("make it 30 minutes"), anyConstraints())
        val revised = ok("revise_workout_draft", "{\"draftId\":11,\"request\":\"make it 30 minutes\"}")
        assertThat(revised.path("draft").path("id").asLong()).isEqualTo(12L)
        assertThat(revised.path("supersededDraftId").asLong()).isEqualTo(11L)

        doReturn(approved()).`when`(approvalService).approve(11L)
        val approval = ok("approve_workout_draft",
            "{\"draftId\":11,\"confirmedByAthlete\":true,\"acknowledgedVersion\":1}")
        assertThat(approval.path("approval").path("approvalId").asLong()).isEqualTo(77L)
        assertThat(approval.path("approval").path("status").asText()).isEqualTo("APPROVED")

        doReturn(readyPreview()).`when`(publishService).preview(11L)
        val preview = ok("preview_draft_publish", "{\"draftId\":11}")
        assertThat(preview.path("preview").path("expectedOutcome").asText()).isEqualTo("PUBLISH")
        assertThat(preview.path("preview").path("renderedWorkoutText").asText()).contains("65-75%")

        doReturn(DraftPublishOutcome(approved(), publication(), 3, false)).`when`(publishService).publish(11L)
        val published = ok("publish_approved_draft",
            "{\"draftId\":11,\"confirmedByAthlete\":true,\"acknowledgedApprovalId\":77}")
        assertThat(published.path("publication").path("outcome").asText()).isEqualTo("PUBLISHED")
        assertThat(published.path("publication").path("alreadyPublished").asBoolean()).isFalse()

        // The adapter never reaches a transport itself: only the service it called may do that.
        verifyNoInteractions(legacyPublish, publisher, intervalsClient)
    }

    @Test
    fun `an omitted date means the athlete's today, not the server's`() {
        doReturn(date).`when`(draftService).today()
        doReturn(draft()).`when`(draftService).generate(eqDate(date), anyConstraints())
        ok("create_workout_draft")
        verify(draftService).generate(eqDate(date), anyConstraints())
    }

    @Test
    fun `constraints reach the coach instead of being silently dropped`() {
        doReturn(date).`when`(draftService).today()
        doReturn(draft()).`when`(draftService).generate(eqDate(date), anyConstraints())
        ok("create_workout_draft",
            "{\"availableMinutes\":35,\"environment\":\"treadmill\",\"userFeedback\":\"legs heavy\"," +
                "\"requestedGoal\":\"easy aerobic\",\"painOrFatigueFeedback\":\"left calf tight\"}")

        val captor = ArgumentCaptor.forClass(SessionConstraints::class.java)
        verify(draftService).generate(eqDate(date), capturedConstraints(captor))
        assertThat(captor.value.availableMinutes).isEqualTo(35)
        assertThat(captor.value.environment).isEqualTo(TrainingEnvironment.TREADMILL)
        assertThat(captor.value.userFeedback).isEqualTo("legs heavy")
        assertThat(captor.value.requestedGoal).isEqualTo("easy aerobic")
        assertThat(captor.value.painOrFatigueFeedback).isEqualTo("left calf tight")
    }

    @Test
    fun `a rest day previews as SKIPPED_REST_DAY and publishes without any external write`() {
        val restApproved = ApprovedWorkoutDraft(
            CoachTestFixtures.restDraft(date = date).copy(id = 13L, status = WorkoutDraftStatus.APPROVED),
            WorkoutDraftApproval(78L, 13L, 1L, date, Instant.parse("2026-10-20T01:00:00Z")),
        )
        doReturn(DraftPublishPreview(restApproved, null, null, emptyList(), null))
            .`when`(publishService).preview(13L)
        val preview = ok("preview_draft_publish", "{\"draftId\":13}")
        assertThat(preview.path("preview").path("expectedOutcome").asText()).isEqualTo("SKIPPED_REST_DAY")
        assertThat(preview.path("preview").path("externalWriteRequired").asBoolean()).isFalse()

        doReturn(DraftPublishOutcome(
            restApproved, publication(100L, 13L, 78L, DraftPublicationOutcome.SKIPPED_REST_DAY), 0, false))
            .`when`(publishService).publish(13L)
        val published = ok("publish_approved_draft",
            "{\"draftId\":13,\"confirmedByAthlete\":true,\"acknowledgedApprovalId\":78}")
        assertThat(published.path("publication").path("outcome").asText()).isEqualTo("SKIPPED_REST_DAY")
        // No Intervals event exists for a rest day. Absent and explicit-null both say that: the
        // MCP structured-content serializer omits null map entries, so this must not demand one.
        val remote = published.path("publication").path("remoteEventId")
        assertThat(remote.isNull || remote.isMissingNode)
            .`as`("a rest day must carry no Intervals event id, got %s", remote)
            .isTrue()
        verifyNoInteractions(publisher, intervalsClient)
    }

    @Test
    fun `re-publishing returns the stored result and says Intervals was not called again`() {
        doReturn(readyPreview()).`when`(publishService).preview(11L)
        doReturn(DraftPublishOutcome(approved(), publication(), null, true)).`when`(publishService).publish(11L)
        val body = ok("publish_approved_draft",
            "{\"draftId\":11,\"confirmedByAthlete\":true,\"acknowledgedApprovalId\":77}")
        assertThat(body.path("publication").path("alreadyPublished").asBoolean()).isTrue()
        assertThat(body.path("note").asText()).contains("NOT called again")
    }

    // ---- 3. an MCP call is not the athlete's approval -----------------------------------------------

    @Test
    fun `approving without the athlete's explicit confirmation is refused and never reaches the service`() {
        doReturn(draft()).`when`(draftService).get(11L)
        // confirmedByAthlete is a REQUIRED argument, so omitting it is a schema violation...
        assertThat(codeOf("approve_workout_draft", "{\"draftId\":11,\"acknowledgedVersion\":1}"))
            .isEqualTo("INVALID_ARGUMENT")
        // ...and supplying it as false is an explicit refusal, never a default.
        assertThat(codeOf("approve_workout_draft",
            "{\"draftId\":11,\"confirmedByAthlete\":false,\"acknowledgedVersion\":1}"))
            .isEqualTo("ATHLETE_CONFIRMATION_REQUIRED")
        verify(approvalService, never()).approve(anyLong())
    }

    @Test
    fun `approving a version the athlete was never shown is refused`() {
        doReturn(draft(version = 3)).`when`(draftService).get(11L)
        val body = failure("approve_workout_draft",
            "{\"draftId\":11,\"confirmedByAthlete\":true,\"acknowledgedVersion\":1}")
        assertThat(body.path("code").asText()).isEqualTo("DRAFT_VERSION_MISMATCH")
        assertThat(body.path("message").asText()).contains("version 3")
        verify(approvalService, never()).approve(anyLong())
    }

    @Test
    fun `publishing without confirmation, or against the wrong approval, never reaches the publish path`() {
        doReturn(readyPreview()).`when`(publishService).preview(11L)
        assertThat(codeOf("publish_approved_draft",
            "{\"draftId\":11,\"confirmedByAthlete\":false,\"acknowledgedApprovalId\":77}"))
            .isEqualTo("ATHLETE_CONFIRMATION_REQUIRED")
        assertThat(codeOf("publish_approved_draft",
            "{\"draftId\":11,\"confirmedByAthlete\":true,\"acknowledgedApprovalId\":1234}"))
            .isEqualTo("APPROVAL_MISMATCH")
        verify(publishService, never()).publish(anyLong())
        verifyNoInteractions(publisher, intervalsClient)
    }

    // ---- 4. domain refusals keep their meaning ------------------------------------------------------

    @Test
    fun `lifecycle refusals arrive with the same codes the REST API returns`() {
        doThrow(WorkoutDraftNotFoundException(404L)).`when`(draftService).get(404L)
        assertThat(codeOf("get_workout_draft", "{\"draftId\":404}")).isEqualTo("WORKOUT_DRAFT_NOT_FOUND")

        doThrow(WorkoutDraftApprovedImmutableException(11L)).`when`(draftService)
            .revise(eqLong(11L), anyText(), anyConstraints())
        assertThat(codeOf("revise_workout_draft", "{\"draftId\":11,\"request\":\"shorter\"}"))
            .isEqualTo("WORKOUT_DRAFT_APPROVED_IMMUTABLE")

        doReturn(draft(id = 12L)).`when`(draftService).get(12L)
        doThrow(WorkoutDraftSupersededException(12L, 2)).`when`(approvalService).approve(12L)
        assertThat(codeOf("approve_workout_draft",
            "{\"draftId\":12,\"confirmedByAthlete\":true,\"acknowledgedVersion\":1}"))
            .isEqualTo("WORKOUT_DRAFT_SUPERSEDED")

        doReturn(draft(id = 14L)).`when`(draftService).get(14L)
        doThrow(WorkoutDateAlreadyApprovedException(14L, date, 11L)).`when`(approvalService).approve(14L)
        assertThat(codeOf("approve_workout_draft",
            "{\"draftId\":14,\"confirmedByAthlete\":true,\"acknowledgedVersion\":1}"))
            .isEqualTo("WORKOUT_DATE_ALREADY_APPROVED")

        doThrow(WorkoutDraftNotApprovedException(15L, WorkoutDraftStatus.DRAFT))
            .`when`(publishService).preview(15L)
        assertThat(codeOf("preview_draft_publish", "{\"draftId\":15}"))
            .isEqualTo("WORKOUT_DRAFT_APPROVAL_REQUIRED")
    }

    @Test
    fun `an unapproved draft can never be published through this tool`() {
        doThrow(WorkoutDraftNotApprovedException(15L, WorkoutDraftStatus.DRAFT))
            .`when`(publishService).preview(15L)
        assertThat(codeOf("publish_approved_draft",
            "{\"draftId\":15,\"confirmedByAthlete\":true,\"acknowledgedApprovalId\":1}"))
            .isEqualTo("WORKOUT_DRAFT_APPROVAL_REQUIRED")
        verify(publishService, never()).publish(anyLong())
        verifyNoInteractions(publisher, intervalsClient)
    }

    @Test
    fun `the injury gate blocks both approval and publishing, and nothing is sent`() {
        doReturn(draft()).`when`(draftService).get(11L)
        doThrow(safetyBlocked()).`when`(approvalService).approve(11L)
        assertThat(codeOf("approve_workout_draft",
            "{\"draftId\":11,\"confirmedByAthlete\":true,\"acknowledgedVersion\":1}"))
            .isEqualTo("WORKOUT_SAFETY_INJURY_ACTIVE")

        doThrow(safetyBlocked()).`when`(publishService).preview(11L)
        assertThat(codeOf("preview_draft_publish", "{\"draftId\":11}"))
            .isEqualTo("WORKOUT_SAFETY_INJURY_ACTIVE")
        verifyNoInteractions(publisher, intervalsClient)
    }

    @Test
    fun `publishing while the master switch is off is refused, not queued`() {
        doReturn(readyPreview()).`when`(publishService).preview(11L)
        doThrow(DraftPublishingDisabledException()).`when`(publishService).publish(11L)
        assertThat(codeOf("publish_approved_draft",
            "{\"draftId\":11,\"confirmedByAthlete\":true,\"acknowledgedApprovalId\":77}"))
            .isEqualTo("DRAFT_PUBLISHING_DISABLED")
    }

    @Test
    fun `a concurrent publish of the same draft is rejected rather than racing`() {
        doReturn(readyPreview()).`when`(publishService).preview(11L)
        doThrow(DraftPublishAlreadyRunningException(11L)).`when`(publishService).publish(11L)
        assertThat(codeOf("publish_approved_draft",
            "{\"draftId\":11,\"confirmedByAthlete\":true,\"acknowledgedApprovalId\":77}"))
            .isEqualTo("DRAFT_PUBLISH_ALREADY_RUNNING")
    }

    @Test
    fun `an unpublishable draft reports every reason instead of being silently adjusted`() {
        doReturn(readyPreview()).`when`(publishService).preview(11L)
        doThrow(UnpublishableDraftException(11L,
            listOf("heart-rate target is in bpm", "treadmill speed has no incline")))
            .`when`(publishService).publish(11L)
        val body = failure("publish_approved_draft",
            "{\"draftId\":11,\"confirmedByAthlete\":true,\"acknowledgedApprovalId\":77}")
        assertThat(body.path("code").asText()).isEqualTo("UNPUBLISHABLE_DRAFT")
        assertThat(body.path("reasons").map { it.asText() })
            .containsExactly("heart-rate target is in bpm", "treadmill speed has no incline")
    }

    @Test
    fun `an Intervals failure keeps its own code so a retry decision can be made`() {
        doReturn(readyPreview()).`when`(publishService).preview(11L)
        doThrow(IntervalsException(IntervalsException.Reason.TIMEOUT, null, "the request timed out"))
            .`when`(publishService).publish(11L)
        assertThat(codeOf("publish_approved_draft",
            "{\"draftId\":11,\"confirmedByAthlete\":true,\"acknowledgedApprovalId\":77}"))
            .isEqualTo("INTERVALS_TIMEOUT")
    }

    // ---- 5. the coach never gets faked ---------------------------------------------------------------

    @Test
    fun `every AI coach failure is reported as a failure, never as an invented workout`() {
        doReturn(date).`when`(draftService).today()
        AiCoachException.Reason.entries.forEach { reason ->
            doThrow(AiCoachException(reason, "coach failed: $reason"))
                .`when`(draftService).generate(eqDate(date), anyConstraints())
            val body = failure("create_workout_draft")
            assertThat(body.path("code").asText()).isEqualTo("AI_COACH_${reason.name}")
            // No workout shape may appear anywhere in a failed answer.
            assertThat(body.has("draft")).`as`("$reason must not return a draft").isFalse()
            assertThat(body.path("message").asText()).isNotBlank()
        }
    }

    @Test
    fun `a missing coach CLI says so in words an operator can act on`() {
        doReturn(date).`when`(draftService).today()
        doThrow(AiCoachException(AiCoachException.Reason.PROVIDER_UNAVAILABLE, "not found on PATH"))
            .`when`(draftService).generate(eqDate(date), anyConstraints())
        val body = failure("create_workout_draft")
        assertThat(body.path("code").asText()).isEqualTo("AI_COACH_PROVIDER_UNAVAILABLE")
        assertThat(body.path("message").asText())
            .contains("not installed or not on this server's PATH")
            .contains("no workout was invented")
    }

    // ---- 6. argument validation -----------------------------------------------------------------------

    @Test
    fun `bad arguments are refused before any service is called`() {
        assertThat(codeOf("get_workout_draft", "{}")).isEqualTo("INVALID_ARGUMENT")
        assertThat(codeOf("get_workout_draft", "{\"draftId\":\"not-a-number\"}")).isEqualTo("INVALID_ARGUMENT")
        assertThat(codeOf("get_workout_draft", "{\"draftId\":11,\"typo\":1}")).isEqualTo("INVALID_ARGUMENT")
        assertThat(codeOf("create_workout_draft", "{\"date\":\"2026-13-45\"}")).isEqualTo("INVALID_ARGUMENT")
        assertThat(codeOf("create_workout_draft", "{\"availableMinutes\":0}")).isEqualTo("INVALID_ARGUMENT")
        assertThat(codeOf("create_workout_draft", "{\"environment\":\"UNDERWATER\"}")).isEqualTo("INVALID_ARGUMENT")
        assertThat(codeOf("revise_workout_draft", "{\"draftId\":11,\"request\":\"   \"}")).isEqualTo("INVALID_ARGUMENT")
        verifyNoInteractions(approvalService, publishService, publisher, intervalsClient)
    }

    @Test
    fun `no failure message leaks a stack trace, a credential or an internal class name`() {
        doThrow(IllegalStateException("jdbc:postgresql://user:pw@host/db blew up"))
            .`when`(draftService).get(404L)
        val body = failure("get_workout_draft", "{\"draftId\":404}")
        assertThat(body.path("code").asText()).isEqualTo("INTERNAL_ERROR")
        assertThat(body.path("message").asText()).isEqualTo("Unexpected server error")
        assertThat(body.toString()).doesNotContain("jdbc").doesNotContain("IllegalStateException")
    }
}
