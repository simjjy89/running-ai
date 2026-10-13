package com.runningai.integration.strava

import com.runningai.enrichment.StravaImportRecord
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Manual import of the athlete's own Strava data export (Phase 7E-2).
 *
 * Explicit trigger only: no scheduler, no startup run, no MCP tool. The source is a local directory,
 * there is no Strava HTTP client anywhere in this repository, and nothing here can publish. Re-invoking
 * `POST` is the resume and the retry - a file whose SHA-256 already has a stored outcome is skipped,
 * and only what failed is attempted again. `dryRun` reports what each file would become without
 * writing anything.
 *
 * No authentication, same convention as the other operational APIs: private network only.
 */
@RestController
@RequestMapping("/api/v1/strava/archive-import")
class StravaArchiveImportController(private val service: StravaArchiveImportService) {

    @PostMapping
    fun import(@RequestBody(required = false) request: StravaArchiveImportRequest?): StravaArchiveImportResult =
        service.importArchive(request ?: StravaArchiveImportRequest())

    /** Everything the importer has recorded, oldest session first. DB-only, reads no file. */
    @GetMapping
    fun records(): List<StravaImportRecord> = service.records()

    @ExceptionHandler(StravaArchiveImportDisabledException::class)
    fun disabled(e: StravaArchiveImportDisabledException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "STRAVA_ARCHIVE_IMPORT_DISABLED", e.message)

    @ExceptionHandler(StravaArchiveImportAlreadyRunningException::class)
    fun alreadyRunning(e: StravaArchiveImportAlreadyRunningException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "STRAVA_ARCHIVE_IMPORT_ALREADY_RUNNING", e.message)

    @ExceptionHandler(UnsafeStravaImportRuntimeException::class)
    fun unsafe(e: UnsafeStravaImportRuntimeException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "STRAVA_ARCHIVE_IMPORT_UNSAFE_RUNTIME", e.message)

    @ExceptionHandler(StravaArchiveException::class)
    fun archive(e: StravaArchiveException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, e.code, e.message)

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(e: IllegalArgumentException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "INVALID_STRAVA_IMPORT_REQUEST", e.message)

    private fun problem(status: HttpStatus, code: String, detail: String?): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail ?: code).apply { setProperty("code", code) }
}
