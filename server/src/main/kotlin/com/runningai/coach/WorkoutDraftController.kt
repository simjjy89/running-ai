package com.runningai.coach

import com.fasterxml.jackson.annotation.JsonInclude
import com.runningai.common.exception.ErrorResponse
import com.runningai.training.IntensityClass
import com.runningai.training.SegmentType
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.Instant
import java.time.LocalDate

/**
 * AI-coach workout drafts: generate, preview, revise, approve. Nothing reachable from here writes
 * to Intervals or Garmin: approval is a database lifecycle step, and publishing an approved draft
 * lives in a separate controller (`com.runningai.draftpublish`) behind its own switch.
 *
 * No authentication, same convention as the other RunningAI operational APIs: private network only.
 */
@RestController
@RequestMapping("/api/v1/workout-drafts")
class WorkoutDraftController(
    private val service: WorkoutDraftService,
    private val approvalService: WorkoutDraftApprovalService,
) {

    /** Designs today's (or the requested date's) session and stores it as version 1. */
    @PostMapping
    fun generate(@Valid @RequestBody request: GenerateWorkoutDraftRequest): WorkoutDraftResponse {
        val date = request.date ?: service.today()
        return WorkoutDraftResponse.of(service.generate(date, request.toConstraints()))
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): WorkoutDraftResponse = WorkoutDraftResponse.of(service.get(id))

    /** Hands the athlete's own words back to the coach, which designs the session again. */
    @PostMapping("/{id}/revisions")
    fun revise(
        @PathVariable id: Long,
        @Valid @RequestBody request: ReviseWorkoutDraftRequest,
    ): WorkoutDraftResponse =
        WorkoutDraftResponse.of(service.revise(id, request.request, request.toConstraints()))

    /**
     * The athlete's explicit approval (Phase 6G). A database lifecycle step only: it never publishes.
     * Approving the same draft again returns the existing approval unchanged.
     */
    @PostMapping("/{id}/approve")
    fun approve(@PathVariable id: Long): WorkoutDraftApprovalResponse =
        WorkoutDraftApprovalResponse.of(approvalService.approve(id))
}

data class GenerateWorkoutDraftRequest(
    @field:DateTimeFormat(iso = DateTimeFormat.ISO.DATE) val date: LocalDate? = null,
    @field:Positive val availableMinutes: Int? = null,
    val environment: TrainingEnvironment? = null,
    val userFeedback: String? = null,
    val requestedGoal: String? = null,
    val painOrFatigueFeedback: String? = null,
) {
    fun toConstraints() = SessionConstraints(
        availableMinutes = availableMinutes,
        environment = environment,
        userFeedback = userFeedback?.takeIf(String::isNotBlank),
        requestedGoal = requestedGoal?.takeIf(String::isNotBlank),
        painOrFatigueFeedback = painOrFatigueFeedback?.takeIf(String::isNotBlank),
    )
}

/**
 * [request] is the athlete's natural-language change. The optional constraint fields let a revision
 * also tighten a hard limit (for example "I now only have 30 minutes") rather than only describing
 * the change in prose.
 */
data class ReviseWorkoutDraftRequest(
    @field:NotBlank val request: String,
    @field:Positive val availableMinutes: Int? = null,
    val environment: TrainingEnvironment? = null,
    val painOrFatigueFeedback: String? = null,
) {
    fun toConstraints() = SessionConstraints(
        availableMinutes = availableMinutes,
        environment = environment,
        userFeedback = request,
        painOrFatigueFeedback = painOrFatigueFeedback?.takeIf(String::isNotBlank),
    )
}

@JsonInclude(JsonInclude.Include.ALWAYS)
data class WorkoutDraftResponse(
    val id: Long?,
    val draftGroupId: String?,
    val version: Int,
    val status: WorkoutDraftStatus,
    val date: LocalDate,
    val title: String,
    val workoutType: String,
    val totalDurationMinutes: Int,
    val assessment: CoachAssessment,
    val segments: List<SegmentResponse>,
    val provider: CoachProvider,
    val model: String?,
    val createdAt: Instant?,
) {
    companion object {
        fun of(d: WorkoutDraft) = WorkoutDraftResponse(
            id = d.id,
            draftGroupId = d.draftGroupId,
            version = d.version,
            status = d.status,
            date = d.date,
            title = d.title,
            workoutType = d.workoutType,
            totalDurationMinutes = d.totalDurationMinutes,
            assessment = d.assessment,
            segments = d.segments.map(SegmentResponse::of),
            provider = d.provider,
            model = d.model,
            createdAt = d.createdAt,
        )
    }
}

@JsonInclude(JsonInclude.Include.ALWAYS)
data class WorkoutDraftApprovalResponse(
    val draftId: Long,
    val draftGroupId: String?,
    val version: Int,
    val date: LocalDate,
    val workoutType: String,
    val status: WorkoutDraftStatus,
    val approvalId: Long,
    val approvedAt: Instant,
) {
    companion object {
        fun of(a: ApprovedWorkoutDraft) = WorkoutDraftApprovalResponse(
            draftId = a.approval.draftId,
            draftGroupId = a.draft.draftGroupId,
            version = a.draft.version,
            date = a.draft.date,
            workoutType = a.draft.workoutType,
            status = a.draft.status,
            approvalId = a.approval.id,
            approvedAt = a.approval.approvedAt,
        )
    }
}

@JsonInclude(JsonInclude.Include.ALWAYS)
data class SegmentResponse(
    val type: SegmentType,
    val durationMinutes: Int,
    val intensity: IntensityClass,
    val description: String?,
    val paceSecondsPerKmFast: Int?,
    val paceSecondsPerKmSlow: Int?,
    val heartRateBpmMin: Int?,
    val heartRateBpmMax: Int?,
    val treadmillSpeedKphMin: Double?,
    val treadmillSpeedKphMax: Double?,
    val inclinePercentMin: Double?,
    val inclinePercentMax: Double?,
    val repetitions: Int?,
    val recoveryDurationMinutes: Int?,
) {
    companion object {
        fun of(s: WorkoutDraftSegment) = SegmentResponse(
            s.type, s.durationMinutes, s.intensity, s.description,
            s.paceSecondsPerKmFast, s.paceSecondsPerKmSlow,
            s.heartRateBpmMin, s.heartRateBpmMax,
            s.treadmillSpeedKphMin, s.treadmillSpeedKphMax,
            s.inclinePercentMin, s.inclinePercentMax,
            s.repetitions, s.recoveryDurationMinutes,
        )
    }
}

/**
 * Maps coach/draft failures to HTTP errors, ahead of the global handler so its catch-all does not
 * turn them into 500s. Messages carry no prompt text, no credential and no raw AI response.
 */
@RestControllerAdvice(assignableTypes = [WorkoutDraftController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class WorkoutDraftExceptionHandler {

    @ExceptionHandler(WorkoutDraftNotFoundException::class)
    fun notFound(e: WorkoutDraftNotFoundException): ResponseEntity<ErrorResponse> =
        body(HttpStatus.NOT_FOUND, "WORKOUT_DRAFT_NOT_FOUND", e.message)

    @ExceptionHandler(WorkoutDraftSupersededException::class)
    fun superseded(e: WorkoutDraftSupersededException): ResponseEntity<ErrorResponse> =
        body(HttpStatus.CONFLICT, "WORKOUT_DRAFT_SUPERSEDED", e.message)

    @ExceptionHandler(WorkoutDraftApprovedImmutableException::class)
    fun approvedImmutable(e: WorkoutDraftApprovedImmutableException): ResponseEntity<ErrorResponse> =
        body(HttpStatus.CONFLICT, "WORKOUT_DRAFT_APPROVED_IMMUTABLE", e.message)

    @ExceptionHandler(WorkoutDateAlreadyApprovedException::class)
    fun dateAlreadyApproved(e: WorkoutDateAlreadyApprovedException): ResponseEntity<ErrorResponse> =
        body(HttpStatus.CONFLICT, "WORKOUT_DATE_ALREADY_APPROVED", e.message)

    @ExceptionHandler(TrainingContextTooLargeException::class)
    fun contextTooLarge(e: TrainingContextTooLargeException): ResponseEntity<ErrorResponse> =
        body(HttpStatus.INTERNAL_SERVER_ERROR, "TRAINING_CONTEXT_TOO_LARGE", e.message)

    @ExceptionHandler(AiCoachException::class)
    fun coach(e: AiCoachException): ResponseEntity<ErrorResponse> {
        val status = when (e.reason) {
            AiCoachException.Reason.PROVIDER_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE
            AiCoachException.Reason.AUTH_REQUIRED -> HttpStatus.SERVICE_UNAVAILABLE
            AiCoachException.Reason.TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT
            AiCoachException.Reason.PROVIDER_ERROR -> HttpStatus.BAD_GATEWAY
            AiCoachException.Reason.INVALID_RESPONSE -> HttpStatus.BAD_GATEWAY
            AiCoachException.Reason.VALIDATION_FAILED -> HttpStatus.UNPROCESSABLE_ENTITY
        }
        return body(status, "AI_COACH_${e.reason.name}", e.message)
    }

    private fun body(status: HttpStatus, code: String, message: String?): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(status).body(ErrorResponse.of(code, message ?: code))
}
