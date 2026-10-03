package com.runningai.backfill

import com.runningai.common.exception.ErrorResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
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

/**
 * Manual historical backfill triggers (Phase 6H-6). Explicit request only: no scheduler, no startup
 * auto-run, no hidden background executor - the POST runs the pipeline on this request thread and
 * returns the run's state (COMPLETED, or PAUSED where it stopped). A PAUSED run is resumed only by a
 * human calling {@code /resume}. No authentication, same convention as the other operational APIs:
 * private network only.
 */
@RestController
@RequestMapping("/api/v1/historical-backfill")
class HistoricalBackfillController(private val service: HistoricalBackfillService) {

    @PostMapping
    fun start(@RequestBody(required = false) request: HistoricalBackfillRequest?): HistoricalBackfillResponse =
        service.start(request ?: HistoricalBackfillRequest())

    @PostMapping("/{runId}/resume")
    fun resume(@PathVariable runId: Long): HistoricalBackfillResponse = service.resume(runId)

    @GetMapping("/{runId}")
    fun status(@PathVariable runId: Long): HistoricalBackfillResponse = service.status(runId)
}

@RestControllerAdvice(assignableTypes = [HistoricalBackfillController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class HistoricalBackfillExceptionHandler {

    @ExceptionHandler(HistoricalBackfillAlreadyRunningException::class)
    fun alreadyRunning(e: HistoricalBackfillAlreadyRunningException): ResponseEntity<ErrorResponse> =
        body(HttpStatus.CONFLICT, "HISTORICAL_BACKFILL_ALREADY_RUNNING", e.message)

    @ExceptionHandler(UnsafeBackfillRuntimeException::class)
    fun unsafe(e: UnsafeBackfillRuntimeException): ResponseEntity<ErrorResponse> =
        body(HttpStatus.CONFLICT, "HISTORICAL_BACKFILL_UNSAFE_RUNTIME", e.message)

    @ExceptionHandler(BackfillNotResumableException::class)
    fun notResumable(e: BackfillNotResumableException): ResponseEntity<ErrorResponse> =
        body(HttpStatus.CONFLICT, e.code, e.message)

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(e: IllegalArgumentException): ResponseEntity<ErrorResponse> =
        body(HttpStatus.BAD_REQUEST, "INVALID_BACKFILL_REQUEST", e.message)

    private fun body(status: HttpStatus, code: String, message: String?): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(status).body(ErrorResponse.of(code, message ?: code))
}
