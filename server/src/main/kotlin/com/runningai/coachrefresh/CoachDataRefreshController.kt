package com.runningai.coachrefresh

import com.runningai.common.exception.ErrorResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.LocalDate

/**
 * Manual trigger for the coach-data-refresh pipeline (Phase 6H-9): keeps the RunningAI DB current
 * just before an operator (or the coach CLI) generates a new AI-coach draft. Never used by
 * `WorkoutDraftController` itself - calling this is a separate, explicit step the operator takes
 * first (see `running-ai-coach.ps1`'s generate path), so [com.runningai.coach.WorkoutDraftService]
 * keeps its existing DB-only, zero-Garmin/Intervals-call contract unchanged.
 *
 * No authentication, same convention as the other RunningAI operational APIs: private network only.
 */
@RestController
@RequestMapping("/api/v1/coach/data-refresh")
class CoachDataRefreshController(private val service: CoachDataRefreshService) {

    @PostMapping
    fun refresh(@RequestBody(required = false) request: CoachDataRefreshRequest?): CoachDataRefreshResult =
        service.refresh(request?.date)
}

data class CoachDataRefreshRequest(
    @field:DateTimeFormat(iso = DateTimeFormat.ISO.DATE) val date: LocalDate? = null,
)

@RestControllerAdvice(assignableTypes = [CoachDataRefreshController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class CoachDataRefreshExceptionHandler {

    @ExceptionHandler(CoachDataRefreshAlreadyRunningException::class)
    fun alreadyRunning(e: CoachDataRefreshAlreadyRunningException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse.of("COACH_DATA_REFRESH_ALREADY_RUNNING", e.message ?: "COACH_DATA_REFRESH_ALREADY_RUNNING"))
}
