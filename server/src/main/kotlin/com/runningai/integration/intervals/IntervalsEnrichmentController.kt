package com.runningai.integration.intervals

import com.runningai.common.exception.ErrorResponse
import com.runningai.enrichment.IntervalsFitnessDayData
import com.runningai.enrichment.SourceLinkConflictException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.LocalDate

/**
 * Manual Intervals.icu enrichment triggers (Phase 6H-5). The POSTs refresh RunningAI's OWN database;
 * towards Intervals.icu every call is a GET through the read-only client — this feature has no code
 * path to an Intervals write. No scheduler, no retry, explicit request only. No authentication, same
 * convention as the other operational APIs: private network only.
 */
@RestController
class IntervalsEnrichmentController(private val service: IntervalsEnrichmentService) {

    @PostMapping("/api/v1/intervals/enrichment/activities/{activityId}")
    fun enrichActivity(@PathVariable activityId: Long): ActivityEnrichmentResult = service.enrichActivity(activityId)

    /** Re-normalises from the stored raw payload. Intervals.icu API calls: zero. */
    @PostMapping("/api/v1/intervals/enrichment/activities/{activityId}/reprocess")
    fun reprocessActivity(@PathVariable activityId: Long): ActivityEnrichmentResult = service.reprocessActivity(activityId)

    @PostMapping("/api/v1/intervals/enrichment/fitness")
    fun enrichFitness(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) oldest: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) newest: LocalDate,
    ): FitnessEnrichmentResult = service.enrichFitness(oldest, newest)

    /** Re-normalises stored wellness payloads of the range. Intervals.icu API calls: zero. */
    @PostMapping("/api/v1/intervals/enrichment/fitness/reprocess")
    fun reprocessFitness(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) oldest: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) newest: LocalDate,
    ): FitnessEnrichmentResult = service.reprocessFitness(oldest, newest)

    /** Stored enrichment of one activity; 404 when it was never enriched. Reads the database only. */
    @GetMapping("/api/v1/activities/{activityId}/intervals")
    fun activityIntervals(@PathVariable activityId: Long): ActivityIntervalsView = service.view(activityId)

    /** Stored daily fitness snapshots of the range. Reads the database only. */
    @GetMapping("/api/v1/intervals/fitness")
    fun fitness(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) oldest: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) newest: LocalDate,
    ): List<IntervalsFitnessDayData> = service.fitnessDays(oldest, newest)
}

/**
 * Maps enrichment failures to HTTP errors; ordered ahead of the global handler so its catch-all does
 * not turn these into 500s. Messages carry codes and field names only — never the API key, an
 * Authorization header or a response body.
 */
@RestControllerAdvice(assignableTypes = [IntervalsEnrichmentController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class IntervalsEnrichmentExceptionHandler {

    @ExceptionHandler(IntervalsEnrichmentAlreadyRunningException::class)
    fun alreadyRunning(e: IntervalsEnrichmentAlreadyRunningException): ResponseEntity<ErrorResponse> =
        body(HttpStatus.CONFLICT, "INTERVALS_ENRICHMENT_ALREADY_RUNNING", e.message)

    @ExceptionHandler(SourceLinkConflictException::class)
    fun linkConflict(e: SourceLinkConflictException): ResponseEntity<ErrorResponse> =
        body(HttpStatus.CONFLICT, "INTERVALS_LINK_CONFLICT", e.message)

    @ExceptionHandler(IntervalsMappingException::class)
    fun mapping(e: IntervalsMappingException): ResponseEntity<ErrorResponse> =
        body(HttpStatus.UNPROCESSABLE_ENTITY, e.code, e.message)

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(e: IllegalArgumentException): ResponseEntity<ErrorResponse> =
        body(HttpStatus.BAD_REQUEST, "INVALID_ENRICHMENT_REQUEST", e.message)

    @ExceptionHandler(IntervalsException::class)
    fun intervals(e: IntervalsException): ResponseEntity<ErrorResponse> {
        val status = when (e.reason) {
            IntervalsException.Reason.AUTH_FAILED -> HttpStatus.UNAUTHORIZED
            IntervalsException.Reason.FORBIDDEN -> HttpStatus.FORBIDDEN
            IntervalsException.Reason.RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS
            IntervalsException.Reason.NOT_CONFIGURED,
            IntervalsException.Reason.CONNECTION_FAILED -> HttpStatus.SERVICE_UNAVAILABLE
            IntervalsException.Reason.TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT
            else -> HttpStatus.BAD_GATEWAY
        }
        return body(status, e.code, e.message)
    }

    private fun body(status: HttpStatus, code: String, message: String?): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(status).body(ErrorResponse.of(code, message ?: code))
}
