package com.runningai.datafreshness

import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/**
 * Read-only data freshness report (Phase 8A). Database-only: it makes no Garmin, Intervals or Claude
 * call, so polling it is free and can never alter what it reports.
 *
 * No authentication, same convention as the other RunningAI operational APIs: private network only.
 */
@RestController
@RequestMapping("/api/v1/data-freshness")
class DataFreshnessController(private val service: DataFreshnessService) {

    /**
     * @param date the reference day ages are measured against; defaults to the athlete's today
     * @param windowDays how far back counts are taken, clamped to 1..365; defaults to 28, the
     *   recovery baseline window. It never limits how far back the newest stored date is looked for.
     */
    @GetMapping
    fun freshness(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate?,
        @RequestParam(required = false) windowDays: Int?,
    ): DataFreshnessResponse = service.freshness(date, windowDays)
}
