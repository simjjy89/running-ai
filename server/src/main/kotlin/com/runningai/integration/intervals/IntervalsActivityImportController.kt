package com.runningai.integration.intervals

import com.runningai.enrichment.IntervalsImportRecord
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Manual import of activities that exist only in Intervals.icu (Phase 7E-1).
 *
 * Explicit trigger only: no scheduler, no startup run, no MCP tool. Read-only towards Intervals.icu and
 * incapable of publishing anything. Re-invoking `POST` is the resume and the retry - every finished
 * activity is skipped from its committed record, and only what failed is attempted again. `dryRun`
 * reports what each candidate would become without writing a row or fetching a stream.
 *
 * No authentication, same convention as the other operational APIs: private network only.
 */
@RestController
@RequestMapping("/api/v1/intervals/activity-import")
class IntervalsActivityImportController(private val service: IntervalsActivityImportService) {

    @PostMapping
    fun import(@RequestBody(required = false) request: IntervalsActivityImportRequest?): IntervalsActivityImportResult =
        service.importRange(request ?: IntervalsActivityImportRequest())

    /** Everything the importer has recorded, oldest session first. DB-only, no Intervals.icu call. */
    @GetMapping
    fun records(): List<IntervalsImportRecord> = service.records()

    /** Re-normalises one imported activity from its stored raw payloads. Zero Intervals.icu calls. */
    @PostMapping("/{intervalsActivityId}/reprocess")
    fun reprocess(@PathVariable intervalsActivityId: String): IntervalsImportRecord =
        service.reprocess(intervalsActivityId)

    @ExceptionHandler(IntervalsActivityImportDisabledException::class)
    fun disabled(e: IntervalsActivityImportDisabledException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "INTERVALS_ACTIVITY_IMPORT_DISABLED", e.message)

    @ExceptionHandler(IntervalsActivityImportAlreadyRunningException::class)
    fun alreadyRunning(e: IntervalsActivityImportAlreadyRunningException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "INTERVALS_ACTIVITY_IMPORT_ALREADY_RUNNING", e.message)

    @ExceptionHandler(UnsafeIntervalsImportRuntimeException::class)
    fun unsafe(e: UnsafeIntervalsImportRuntimeException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "INTERVALS_ACTIVITY_IMPORT_UNSAFE_RUNTIME", e.message)

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(e: IllegalArgumentException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "INVALID_INTERVALS_IMPORT_REQUEST", e.message)

    private fun problem(status: HttpStatus, code: String, detail: String?): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail ?: code).apply { setProperty("code", code) }
}
