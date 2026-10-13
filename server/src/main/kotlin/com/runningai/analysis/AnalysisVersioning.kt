package com.runningai.analysis

import com.runningai.activity.Activity
import com.runningai.activity.detail.DetailPartRecord
import java.security.MessageDigest

/**
 * Identity and reuse policy of a stored analysis (Phase 7E-1).
 *
 * Phase 7E-0 measured what a single version string costs: [RUNNING_ANALYSIS_VERSION] did not change
 * when V22 added the running-dynamics metrics, so every "is the stored analysis current?" check in the
 * system - the historical backfill reuse rule, its VERIFY phase, the coach freshness evaluator -
 * answered yes for rows the pre-V22 engine had produced with empty dynamics. Re-running the backfill
 * could not repair them: there was nothing in the row to notice.
 *
 * So a stored analysis carries two independent facts, and reuse needs a third:
 *
 *  - **contract** ([RUNNING_ANALYSIS_VERSION]) - the output shape consumers read. Changes only when a
 *    field/column appears, disappears or changes meaning.
 *  - **engine revision** ([RUNNING_ANALYSIS_ENGINE_REVISION]) - the logic that produced the numbers.
 *    Bumped by whoever changes a calculator, adds a metric or changes which input is read. A contract
 *    change that also changes numbers bumps both.
 *  - **input digest** ([AnalysisInputFingerprint]) - what the engine was fed. Detects "the data
 *    changed" without re-running the engine to find out.
 *
 * The rule for future engine changes is therefore mechanical rather than a judgement call each time:
 * **if a code change can alter any value this package writes, bump [RUNNING_ANALYSIS_ENGINE_REVISION]
 * in the same commit.** Everything that decides whether to recompute goes through [AnalysisReusePolicy]
 * and will then pick the change up on the next pass, with no migration and no manual sweep list.
 */

/**
 * The output CONTRACT of the analysis - the field and column shape callers depend on.
 *
 * Deliberately unchanged by Phase 7E-1: the shape consumers read did not change, so renaming it would
 * only invalidate their expectations without describing anything new. What changed (the engine) is the
 * revision's job to describe.
 */
const val RUNNING_ANALYSIS_VERSION: String = "RUNNING_ANALYSIS_V1"

/**
 * The LOGIC that produced the stored numbers.
 *
 *  - `0` reserved for rows stored before revisions were tracked (set by V24). Never produced by this
 *    code, always a re-analysis target: which engine wrote them is not recorded anywhere, and guessing
 *    it from which columns happen to be NULL would be exactly the kind of inference this package bans.
 *  - `1` Phase 6H-4 engine: session / zone / threshold / lap metrics and interval structure.
 *  - `2` Phase 6H-8 (V22): running dynamics (stride length, vertical oscillation and ratio, ground
 *    contact time, running power) added to the same contract.
 *
 * **Bump this whenever a change can alter a value this package writes.** Add the new revision to the
 * list above in the same commit, so the history stays readable.
 */
const val RUNNING_ANALYSIS_ENGINE_REVISION: Int = 2

/** Why [AnalysisReusePolicy] asked for a recomputation - a fact about the stored row, never a verdict. */
enum class ReanalysisReason {
    /** Nothing is stored for the activity yet. */
    NO_STORED_ANALYSIS,

    /** The stored row states a different output contract. */
    CONTRACT_CHANGED,

    /** The stored row was produced by an older (or unrecorded) engine revision. */
    ENGINE_REVISION_CHANGED,

    /** The stored row has no input digest, so "did the data change?" cannot be answered. */
    INPUT_DIGEST_UNKNOWN,

    /** The inputs the stored row was computed from are not the inputs available now. */
    INPUT_CHANGED,
}

/** Reuse the stored analysis, or recompute it - and for a recomputation, why. */
sealed interface AnalysisReuseDecision {
    data object Reuse : AnalysisReuseDecision
    data class Reanalyse(val reason: ReanalysisReason) : AnalysisReuseDecision
}

/**
 * A digest of everything [RunningActivityAnalysisService] reads, computed without reading the sample
 * rows themselves.
 *
 * The expensive inputs (samples, laps, zones) are represented by what the last collection *recorded*
 * about them in `activity_detail_collection`: the status of the part, how many rows it wrote and, for
 * the sample stream, its fidelity. Re-collecting a stream with more points, adding laps or a part
 * failing all change that record. Deciding whether to recompute therefore costs one small query, not a
 * scan of tens of thousands of sample rows.
 *
 * What is deliberately NOT in the digest: `attempted_at` and any other timestamp. A re-collection that
 * produced byte-identical data must not look like a data change, or every collection pass would force
 * a pointless recomputation of every activity.
 */
object AnalysisInputFingerprint {

    fun of(activity: Activity, parts: List<DetailPartRecord>, lactateThresholdHrBpm: Int?): String {
        val canonical = buildString {
            append("activity=").append(activity.id?.toString().orEmpty())
            append("|type=").append(activity.activityType)
            append("|startedAt=").append(activity.startedAt.epochSecond)
            append("|duration=").append(activity.durationSeconds)
            append("|distance=").append(activity.distanceMeters ?: "")
            append("|lthr=").append(lactateThresholdHrBpm ?: "")
            parts.sortedBy { it.payloadType }.forEach { part ->
                append("|part=").append(part.payloadType)
                append(':').append(part.status)
                append(':').append(part.itemCount ?: "")
                append(':').append(part.sampleFidelity?.completeness ?: "")
                append(':').append(part.sampleFidelity?.sourceTotalMetricsCount ?: "")
            }
        }
        return sha256(canonical)
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
}

/**
 * The single place that answers "is this stored analysis still the right one?".
 *
 * Every caller that used to compare [ActivityAnalysis.analysisVersion] to a constant goes through here
 * instead, so a future engine change is picked up everywhere at once.
 */
object AnalysisReusePolicy {

    /** Whether [analysis] was produced by the current contract AND the current engine revision. */
    fun isCurrent(analysis: ActivityAnalysis?): Boolean =
        analysis != null &&
            analysis.analysisVersion == RUNNING_ANALYSIS_VERSION &&
            analysis.analysisEngineRevision == RUNNING_ANALYSIS_ENGINE_REVISION

    /**
     * Whether [stored] may be reused given the inputs available now ([currentInputDigest]).
     *
     * A stored row without a digest is never reused: it predates input tracking, and treating "I do not
     * know whether the data changed" as "it did not" is the exact failure Phase 7E-0 found.
     */
    fun decide(stored: ActivityAnalysis?, currentInputDigest: String): AnalysisReuseDecision = when {
        stored == null -> AnalysisReuseDecision.Reanalyse(ReanalysisReason.NO_STORED_ANALYSIS)
        stored.analysisVersion != RUNNING_ANALYSIS_VERSION ->
            AnalysisReuseDecision.Reanalyse(ReanalysisReason.CONTRACT_CHANGED)
        stored.analysisEngineRevision != RUNNING_ANALYSIS_ENGINE_REVISION ->
            AnalysisReuseDecision.Reanalyse(ReanalysisReason.ENGINE_REVISION_CHANGED)
        stored.inputDigest == null -> AnalysisReuseDecision.Reanalyse(ReanalysisReason.INPUT_DIGEST_UNKNOWN)
        stored.inputDigest != currentInputDigest -> AnalysisReuseDecision.Reanalyse(ReanalysisReason.INPUT_CHANGED)
        else -> AnalysisReuseDecision.Reuse
    }
}
