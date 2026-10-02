package com.runningai.draftpublish

import com.fasterxml.jackson.annotation.JsonInclude
import com.runningai.coach.WorkoutDraftNotFoundException
import com.runningai.coach.WorkoutDraftStatus
import com.runningai.common.exception.ErrorResponse
import com.runningai.integration.intervals.IntervalsException
import com.runningai.integration.intervals.IntervalsPublishOperation
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.Instant
import java.time.LocalDate

/**
 * Preview and publish of APPROVED AI-coach drafts (Phase 6G). Not to be confused with the legacy
 * deterministic `POST /api/v1/workout-publish`, which computes its own prescription for a date.
 *
 * No authentication, same convention as the other RunningAI operational APIs: private network only.
 */
@RestController
@RequestMapping("/api/v1/workout-drafts")
class DraftPublishController(private val service: ApprovedWorkoutDraftPublishService) {

    /** Exactly what a publish would send. Read-only, works with the publish switch off, never calls Intervals. */
    @GetMapping("/{id}/publish-preview")
    fun preview(@PathVariable id: Long): DraftPublishPreviewResponse = DraftPublishPreviewResponse.of(service.preview(id))

    /** Publishes the approved draft (or records SKIPPED_REST_DAY for a rest day). Explicit request only. */
    @PostMapping("/{id}/publish")
    fun publish(@PathVariable id: Long): DraftPublishResponse = DraftPublishResponse.of(service.publish(id))
}

enum class DraftPublishExpectedOutcome { PUBLISH, SKIPPED_REST_DAY, UNPUBLISHABLE }

@JsonInclude(JsonInclude.Include.ALWAYS)
data class DraftPublishPreviewResponse(
    val draftId: Long,
    val approvalId: Long,
    val date: LocalDate,
    val workoutType: String,
    val restDay: Boolean,
    val publishable: Boolean,
    val externalWriteRequired: Boolean,
    val expectedOutcome: DraftPublishExpectedOutcome,
    /** Intervals.icu Workout Builder text; for a rest day the explicit [REST_DAY_REPRESENTATION], never "". */
    val renderedWorkoutText: String?,
    val structuredStepCount: Int,
    val unpublishableReasons: List<String>,
    /** The stored publication when this draft was already published, else null. */
    val publication: DraftPublicationResponse?,
) {
    companion object {
        const val REST_DAY_REPRESENTATION = "REST DAY - approved rest; no workout is sent to Intervals.icu or Garmin"

        fun of(p: DraftPublishPreview) = DraftPublishPreviewResponse(
            draftId = p.approved.approval.draftId,
            approvalId = p.approved.approval.id,
            date = p.approved.draft.date,
            workoutType = p.approved.draft.workoutType,
            restDay = p.restDay,
            publishable = p.publishable,
            externalWriteRequired = !p.restDay && p.publishable,
            expectedOutcome = when {
                p.restDay -> DraftPublishExpectedOutcome.SKIPPED_REST_DAY
                p.publishable -> DraftPublishExpectedOutcome.PUBLISH
                else -> DraftPublishExpectedOutcome.UNPUBLISHABLE
            },
            renderedWorkoutText = if (p.restDay) REST_DAY_REPRESENTATION else p.rendered?.workoutText(),
            structuredStepCount = p.workout?.steps()?.size ?: 0,
            unpublishableReasons = p.unpublishableReasons,
            publication = p.publication?.let(DraftPublicationResponse::of),
        )
    }
}

@JsonInclude(JsonInclude.Include.ALWAYS)
data class DraftPublicationResponse(
    val publicationId: Long,
    val outcome: DraftPublicationOutcome,
    val intervalsOperation: IntervalsPublishOperation?,
    val remoteEventId: String?,
    val verified: Boolean?,
    val publishedAt: Instant,
) {
    companion object {
        fun of(p: WorkoutDraftPublication) = DraftPublicationResponse(
            p.id, p.outcome, p.intervalsOperation, p.remoteEventId, p.verified, p.publishedAt,
        )
    }
}

@JsonInclude(JsonInclude.Include.ALWAYS)
data class DraftPublishResponse(
    val draftId: Long,
    val approvalId: Long,
    val date: LocalDate,
    val workoutType: String,
    val outcome: DraftPublicationOutcome,
    val intervalsOperation: IntervalsPublishOperation?,
    val remoteEventId: String?,
    val verified: Boolean?,
    val publishedAt: Instant,
    val structuredStepCount: Int?,
    /** True when this request returned the stored result of an earlier publish without calling Intervals. */
    val alreadyPublished: Boolean,
) {
    companion object {
        fun of(o: DraftPublishOutcome) = DraftPublishResponse(
            draftId = o.publication.draftId,
            approvalId = o.publication.approvalId,
            date = o.approved.draft.date,
            workoutType = o.approved.draft.workoutType,
            outcome = o.publication.outcome,
            intervalsOperation = o.publication.intervalsOperation,
            remoteEventId = o.publication.remoteEventId,
            verified = o.publication.verified,
            publishedAt = o.publication.publishedAt,
            structuredStepCount = o.structuredStepCount,
            alreadyPublished = o.alreadyPublished,
        )
    }
}

/**
 * Maps draft-publish failures to HTTP errors, ahead of the global handler. Messages carry no
 * credential, no response body, no prompt text and no workout text.
 */
@RestControllerAdvice(assignableTypes = [DraftPublishController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class DraftPublishExceptionHandler {

    @ExceptionHandler(WorkoutDraftNotFoundException::class)
    fun notFound(e: WorkoutDraftNotFoundException) = body(HttpStatus.NOT_FOUND, "WORKOUT_DRAFT_NOT_FOUND", e.message)

    @ExceptionHandler(WorkoutDraftNotApprovedException::class)
    fun notApproved(e: WorkoutDraftNotApprovedException): ResponseEntity<ErrorResponse> = when (e.status) {
        WorkoutDraftStatus.SUPERSEDED -> body(HttpStatus.CONFLICT, "WORKOUT_DRAFT_SUPERSEDED", e.message)
        else -> body(HttpStatus.CONFLICT, "WORKOUT_DRAFT_APPROVAL_REQUIRED", e.message)
    }

    // 409, as the legacy WORKOUT_PUBLISHING_DISABLED: the request conflicts with the current operating state.
    @ExceptionHandler(DraftPublishingDisabledException::class)
    fun disabled(e: DraftPublishingDisabledException) =
        body(HttpStatus.CONFLICT, "DRAFT_PUBLISHING_DISABLED", e.message)

    @ExceptionHandler(DraftPublishAlreadyRunningException::class)
    fun alreadyRunning(e: DraftPublishAlreadyRunningException) =
        body(HttpStatus.CONFLICT, "DRAFT_PUBLISH_ALREADY_RUNNING", e.message)

    @ExceptionHandler(UnpublishableDraftException::class)
    fun unpublishable(e: UnpublishableDraftException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
            ErrorResponse.of(
                "UNPUBLISHABLE_DRAFT",
                "The approved draft cannot be published without changing it; nothing was sent",
                e.reasons.map { ErrorResponse.FieldError("draft", it) },
            ),
        )

    /** Same status mapping as the legacy publish endpoint, so an Intervals failure reads the same on both paths. */
    @ExceptionHandler(IntervalsException::class)
    fun intervals(e: IntervalsException): ResponseEntity<ErrorResponse> {
        val status = when (e.reason) {
            IntervalsException.Reason.AUTH_FAILED -> HttpStatus.UNAUTHORIZED
            IntervalsException.Reason.FORBIDDEN -> HttpStatus.FORBIDDEN
            IntervalsException.Reason.RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS
            IntervalsException.Reason.NOT_CONFIGURED, IntervalsException.Reason.CONNECTION_FAILED -> HttpStatus.SERVICE_UNAVAILABLE
            IntervalsException.Reason.TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT
            IntervalsException.Reason.CLIENT_ERROR, IntervalsException.Reason.UPSTREAM_ERROR,
            IntervalsException.Reason.INVALID_RESPONSE, IntervalsException.Reason.READBACK_MISMATCH -> HttpStatus.BAD_GATEWAY
            IntervalsException.Reason.DUPLICATE_OWNED_WORKOUT, IntervalsException.Reason.UNMANAGED_WORKOUT_CONFLICT -> HttpStatus.CONFLICT
            IntervalsException.Reason.EMPTY_WORKOUT -> HttpStatus.UNPROCESSABLE_ENTITY
        }
        return body(status, e.code, e.message)
    }

    private fun body(status: HttpStatus, code: String, message: String?): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(status).body(ErrorResponse.of(code, message ?: code))
}
