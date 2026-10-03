package com.runningai.coach

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.runningai.common.exception.ErrorResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/**
 * Read-only preview of the context a coach call would receive (Phase 6H-7), without calling the
 * coach: **DB read only — zero Garmin calls, zero Intervals calls, zero Claude calls.** The context
 * itself is already sanitized (no raw payload, external id, GPS or credential ever enters a
 * [CoachTrainingContext]), so the response needs no further redaction.
 */
@RestController
@RequestMapping("/api/v1/coach/training-context")
class TrainingContextPreviewController(
    private val contextBuilder: CoachTrainingContextBuilder,
    private val serializer: CoachContextSerializer,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {

    @GetMapping
    fun preview(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate?,
        @RequestParam(required = false) version: ContextVersion?,
    ): TrainingContextPreviewResponse {
        val resolvedDate = date ?: contextBuilder.today()
        val context = version
            ?.let { contextBuilder.build(resolvedDate, SessionConstraints(), it) }
            ?: contextBuilder.build(resolvedDate, SessionConstraints())
        val snapshot = serializer.snapshot(context, Instant.now(clock))
        return TrainingContextPreviewResponse(
            contextVersion = snapshot.version,
            sizeBytes = snapshot.json.toByteArray(Charsets.UTF_8).size,
            sha256 = snapshot.sha256,
            context = objectMapper.readTree(snapshot.json),
        )
    }
}

data class TrainingContextPreviewResponse(
    val contextVersion: ContextVersion,
    val sizeBytes: Int,
    val sha256: String,
    val context: JsonNode,
)

@RestControllerAdvice(assignableTypes = [TrainingContextPreviewController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class TrainingContextPreviewExceptionHandler {

    @ExceptionHandler(TrainingContextTooLargeException::class)
    fun tooLarge(e: TrainingContextTooLargeException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse.of("TRAINING_CONTEXT_TOO_LARGE", e.message ?: "context too large"))
}
