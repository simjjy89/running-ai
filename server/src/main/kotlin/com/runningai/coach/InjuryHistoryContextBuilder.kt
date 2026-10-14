package com.runningai.coach

import com.runningai.injury.InjuryReport
import com.runningai.injury.InjurySafetyProperties
import com.runningai.injury.InjuryStateService
import com.runningai.injury.InjuryStatus
import com.runningai.injury.WorkoutSafetyPolicy
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * Caps on what the injury ledger may contribute to a coach prompt (Phase 7F-3A). Plain Kotlin
 * defaults rather than `@DefaultValue`, so the class is directly constructible in tests, exactly
 * like [TrainingContextV2Properties].
 */
@ConfigurationProperties(prefix = "running-ai.coach.injury-history")
data class InjuryHistoryContextProperties(
    /** How far back [InjuryHistoryContext.previousReports] may reach. */
    val historyWindowDays: Int = 180,
    /** How many reports older than the latest are listed, newest first. */
    val maxPreviousReports: Int = 4,
    /**
     * Longest free-text note excerpt carried for the latest report. 0 disables excerpts entirely and
     * leaves only `notesPresent`, for an operator who would rather no free text reach the model at
     * all; the structured symptom fields are unaffected either way.
     */
    val maxNotesExcerptChars: Int = 200,
)

/**
 * Builds [InjuryHistoryContext] for both context versions (Phase 7F-3A).
 *
 * Deliberately reads the ledger **once** and evaluates [WorkoutSafetyPolicy] against that same read,
 * rather than calling `WorkoutSafetyGateService.currentDecision()` separately: the gate verdict the
 * coach is shown then describes exactly the reports listed beside it, with no window in which a
 * report landing between two reads could make the two disagree. This changes nothing about
 * enforcement - [com.runningai.coach.WorkoutDraftService] still asks the real gate, independently,
 * both before and after the coach call.
 */
@Service
@Transactional(readOnly = true)
class InjuryHistoryContextBuilder(
    private val injuryStateService: InjuryStateService,
    private val policy: WorkoutSafetyPolicy,
    private val safetyProperties: InjurySafetyProperties,
    private val properties: InjuryHistoryContextProperties,
    private val clock: Clock,
) {

    fun build(): InjuryHistoryContext {
        // Hour granularity for every age in this context, and no "evaluatedAt" instant: a draft
        // generated twice within the same hour then produces a byte-identical context snapshot, so
        // CoachContextSerializer's SHA-256 still means "the evidence changed", not "time passed".
        val now = Instant.now(clock)
        val ledger = injuryStateService.history() // newest first
        val latest = ledger.firstOrNull()
        val decision = policy.evaluate(latest, now)

        val windowStart = now.minusSeconds(properties.historyWindowDays.toLong() * SECONDS_PER_DAY)
        val inWindow = ledger.filter { timestampOf(it).isAfter(windowStart) }
        val previous = inWindow.drop(1).take(properties.maxPreviousReports.coerceAtLeast(0))
        val listed = (if (latest == null) 0 else 1) + previous.size

        return InjuryHistoryContext(
            gate = InjuryGateContext(
                nonRestTrainingAllowed = decision.nonRestTrainingAllowed,
                reasonCode = decision.reasonCode,
                message = decision.message,
                stalenessHours = safetyProperties.stalenessHours,
            ),
            totalReportCount = ledger.size,
            historyWindowDays = properties.historyWindowDays,
            reportsInWindow = inWindow.size,
            omittedReportCount = ledger.size - listed,
            latest = latest?.let { InjuryReportContext.of(it, now, properties.maxNotesExcerptChars) },
            previousReports = previous.map { InjuryReportContext.of(it, now, notesExcerptChars = null) },
            // Over the WHOLE ledger, not the window: a flare-up that scrolled out of the listed
            // history is still the thing the athlete is returning to training from.
            daysSinceLatestActiveReport = ledger.firstOrNull { it.status == InjuryStatus.ACTIVE }
                ?.let { InjuryReportContext.wholeHoursBetween(timestampOf(it), now) / 24 }
                ?.toInt(),
        )
    }

    private fun timestampOf(report: InjuryReport): Instant = report.recordedAt ?: report.reportedAt

    private companion object {
        const val SECONDS_PER_DAY = 86_400L
    }
}
