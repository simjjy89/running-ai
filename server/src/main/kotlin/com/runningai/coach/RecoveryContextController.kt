package com.runningai.coach

import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/**
 * Read-only view of exactly the [RecoveryContext] the AI coach receives for a date (Phase 6F), so an
 * operator can check the stored recovery data and its baselines without generating a draft.
 * Missing metrics are serialized as explicit nulls, as in the coach prompt.
 */
@RestController
@RequestMapping("/api/v1/recovery-context")
class RecoveryContextController(
    private val recoveryContextBuilder: RecoveryContextBuilder,
    private val trainingContextBuilder: TrainingContextBuilder,
) {

    @GetMapping
    fun get(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate?,
    ): RecoveryContext = recoveryContextBuilder.build(date ?: trainingContextBuilder.today())
}
