package com.runningai.integration.garmin

import com.runningai.common.exception.ErrorResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Manual trigger for one activity's detail collection (Phase 6H-1A). Explicit request only: there is
 * no scheduler and no batch/backfill. `reprocess` re-normalises stored raw payloads without any Garmin
 * call. Connector failures map like every Garmin sync endpoint (`GarminSyncExceptionHandler`).
 * No authentication, same convention as the other operational APIs: private network only.
 */
@RestController
@RequestMapping("/api/v1/garmin/activities/{garminActivityId:[1-9][0-9]{0,18}}/details")
class GarminActivityDetailController(private val service: GarminActivityDetailIngestionService) {

    @PostMapping
    fun collect(@PathVariable garminActivityId: String): DetailCollectionResult = service.collect(garminActivityId)

    @PostMapping("/reprocess")
    fun reprocess(@PathVariable garminActivityId: String): DetailCollectionResult = service.reprocess(garminActivityId)
}

@RestControllerAdvice(assignableTypes = [GarminActivityDetailController::class])
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class GarminActivityDetailExceptionHandler {

    @ExceptionHandler(GarminDetailCollectionAlreadyRunningException::class)
    fun alreadyRunning(e: GarminDetailCollectionAlreadyRunningException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse.of("GARMIN_DETAIL_COLLECTION_ALREADY_RUNNING", e.message ?: "already running"))

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalidId(e: IllegalArgumentException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of("INVALID_GARMIN_ACTIVITY_ID", e.message ?: "invalid id"))
}
