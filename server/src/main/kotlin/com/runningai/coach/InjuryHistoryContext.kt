package com.runningai.coach

import com.runningai.injury.InjuryReport
import com.runningai.injury.InjurySafetyReasonCode
import com.runningai.injury.InjurySide
import com.runningai.injury.InjuryStatus
import java.time.Duration
import java.time.Instant

/**
 * What the athlete has actually reported about pain and injury, as the AI coach sees it
 * (Phase 7F-3A).
 *
 * Why this exists: the Phase 7B-2 safety gate already blocks non-REST training while a report is
 * ACTIVE, UNKNOWN, stale or absent, and in those cases
 * [com.runningai.injury.WorkoutSafetyGateService.constraintText] tells the coach so in words. But
 * the moment the athlete reports RESOLVED the gate falls silent - `constraintText` returns null -
 * and the coach was then designing a session with **no knowledge at all** that the athlete had been
 * injured days earlier. This context closes exactly that gap, and nothing else: the gate is
 * untouched and still decides, alone, whether non-REST training may be stored or published.
 *
 * Three rules hold throughout, the same ones that hold for the rest of the context:
 *  - a `null` field means the athlete **did not report that item** - unknown, never "no symptom";
 *  - nothing here is a judgement. RunningAI states what was reported and how long ago; whether that
 *    means today's session should be easier is the coach's call, and whether the athlete has
 *    actually healed is nobody's call in this system; and
 *  - history is capped, never rewritten. A later RESOLVED report does not delete an earlier ACTIVE
 *    one: older rows drop out of [previousReports] only by the explicit
 *    [InjuryHistoryContextProperties] caps, which [omittedReportCount] then states out loud, and
 *    [daysSinceLatestActiveReport] is computed over the **whole** ledger so a past flare-up stays
 *    visible even when its row is no longer listed.
 */
data class InjuryHistoryContext(
    /** The server-enforced Phase 7B-2 verdict on [latest]. Authoritative; the coach cannot widen it. */
    val gate: InjuryGateContext,
    /** Every report ever recorded for this athlete, listed or not. */
    val totalReportCount: Int,
    /** How many days back [previousReports] may reach. */
    val historyWindowDays: Int,
    /** How many reports fall inside that window, listed or not. */
    val reportsInWindow: Int,
    /** Reports that exist but are not listed below, because of the window or the per-call cap. */
    val omittedReportCount: Int,
    /** The most recent report - the one [gate] evaluated. Null only when none has ever been made. */
    val latest: InjuryReportContext?,
    /** Older reports, newest first, capped. Never includes [latest]. */
    val previousReports: List<InjuryReportContext>,
    /**
     * Whole days between the most recent ACTIVE report in the entire ledger and now, or null when
     * the athlete has never reported an ACTIVE symptom. A small number here means today's session
     * is a return to training after a recent injury, whatever [latest] says.
     */
    val daysSinceLatestActiveReport: Int?,
)

/**
 * The Phase 7B-2 safety decision, restated for the coach as a fact rather than as an instruction.
 * [nonRestTrainingAllowed] false is already enforced server-side (and spelled out in
 * `constraints.painOrFatigueFeedback`); true means only that the stored data permits a non-REST
 * session to be *considered*, never that one is medically advisable.
 */
data class InjuryGateContext(
    val nonRestTrainingAllowed: Boolean,
    val reasonCode: InjurySafetyReasonCode,
    val message: String,
    /** How old a report may get before the gate stops trusting it (`running-ai.injury-safety`). */
    val stalenessHours: Long,
)

/**
 * One self-reported symptom status. Every symptom field is independently optional and a null one is
 * **unreported**, not negative: an athlete who only says "knee, RESOLVED" has not thereby said that
 * walking is pain-free.
 *
 * [notesExcerpt] is the athlete's own free text, length-capped and carried for [InjuryHistoryContext.latest]
 * only. The raw note is never dumped into a prompt wholesale: it is unstructured text that can hold
 * anything the athlete typed, so the structured fields above are what the coach is meant to reason
 * from, and the excerpt is context, not evidence. [notesPresent] stays true even when no excerpt is
 * carried, so the coach is never misled into thinking nothing was written.
 */
data class InjuryReportContext(
    val status: InjuryStatus,
    /** When the athlete says the status applies from. */
    val reportedAt: Instant,
    /** When RunningAI stored it (server time, never backdated); null only for a pre-7B-2 row. */
    val recordedAt: Instant?,
    /** Whole hours since the report was recorded. Hour granularity on purpose: see the builder. */
    val ageHours: Long,
    val bodySite: String?,
    val side: InjurySide?,
    val painPresent: Boolean?,
    val painIntensity: Int?,
    val painOnWalking: Boolean?,
    val painOnStairs: Boolean?,
    val swelling: Boolean?,
    val rangeOfMotionLimited: Boolean?,
    val notesPresent: Boolean,
    val notesExcerpt: String?,
    val notesTruncated: Boolean,
) {
    companion object {

        fun of(report: InjuryReport, now: Instant, notesExcerptChars: Int?): InjuryReportContext {
            val notes = report.notes?.takeIf(String::isNotBlank)
            val excerpt = notesExcerptChars
                ?.takeIf { it > 0 }
                ?.let { limit -> notes?.let { if (it.length > limit) it.take(limit) else it } }
            return InjuryReportContext(
                status = report.status,
                reportedAt = report.reportedAt,
                recordedAt = report.recordedAt,
                ageHours = wholeHoursBetween(report.recordedAt ?: report.reportedAt, now),
                bodySite = report.bodySite,
                side = report.side,
                painPresent = report.painPresent,
                painIntensity = report.painIntensity,
                painOnWalking = report.painOnWalking,
                painOnStairs = report.painOnStairs,
                swelling = report.swelling,
                rangeOfMotionLimited = report.rangeOfMotionLimited,
                notesPresent = notes != null,
                notesExcerpt = excerpt,
                notesTruncated = excerpt != null && notes != null && excerpt.length < notes.length,
            )
        }

        /** Never negative: a report whose timestamp is slightly ahead of the clock reads as age 0. */
        internal fun wholeHoursBetween(from: Instant, to: Instant): Long =
            Duration.between(from, to).toHours().coerceAtLeast(0)
    }
}
