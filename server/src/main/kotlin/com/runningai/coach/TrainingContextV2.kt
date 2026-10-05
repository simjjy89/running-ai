package com.runningai.coach

import com.runningai.activity.ActivityType
import com.runningai.activity.detail.SampleCompleteness
import com.runningai.analysis.AnalysisStatus
import java.time.LocalDate

/**
 * Evidence-rich training context (Phase 6H-7): everything RunningAI has actually stored about this
 * athlete — Garmin detail, RunningAI Analysis, Intervals.icu training-model metrics, Garmin recovery —
 * compacted into a snapshot small and specific enough for a coach to read, never a raw data dump.
 *
 * Three rules hold throughout, exactly as for [TrainingContext]:
 *  - a `null` value means **genuinely unknown**, never "assume a default";
 *  - nothing here is a raw external payload, identifier or GPS coordinate — see
 *    `docs/architecture/training-context-v2.md` for the full provenance/compaction table; and
 *  - this is **evidence, not a decision**: no rating, no coaching rule and no candidate workout type
 *    is computed here (compare [RecentTraining.candidateTrainingTypes] in V1, deliberately absent
 *    from V2 — the coach chooses the session type from the evidence, not from a pre-narrowed list).
 */
data class TrainingContextV2(
    val contextVersion: ContextVersion = ContextVersion.V2,
    override val date: LocalDate,
    override val athlete: AthleteThresholds,
    val dataCoverage: DataCoverageV2,
    /** Garmin recovery (HRV, sleep, resting HR, Body Battery, stress); reuses [RecoveryContext] as-is. */
    val recovery: RecoveryContext,
    val trainingLoad: TrainingLoadContextV2,
    val trainingRhythm: TrainingRhythmV2,
    val recentActivities: List<RecentActivityEvidence>,
    /** Additive (Phase 6H-9): when each DB-stored source was last current, nothing about *why*. */
    val sourceFreshness: SourceFreshnessV2,
    override val constraints: SessionConstraints,
) : CoachTrainingContext

/**
 * When each DB-stored source was last current, by source date / age in days - **not** a verdict on
 * whether the coach should trust it (that reasoning is the coach's own, see
 * `ClaudeCoachPromptBuilder`'s freshness guidance).
 *
 * Deliberately does **not** include a Garmin incremental-sync timestamp: that would require
 * `TrainingContextV2Builder` to import `com.runningai.integration.garmin`, which
 * `CoachArchitectureTest` forbids for every file under `com.runningai.coach` (this package must stay
 * DB-only and dependency-free of any Garmin/Intervals transport type, not just free of making a
 * call). Whether the Garmin source itself was recently and successfully synced is reported only by
 * `POST /api/v1/coach/data-refresh` (`com.runningai.coachrefresh`), the separate, explicit step that
 * runs immediately before a new draft is generated - never inside this context.
 *
 * Section 37 (critical, Phase 6H-9): [newestActivityDate] being old or absent is a plain fact, never
 * itself a staleness signal - an athlete can legitimately not have trained in days. It is repeated
 * here (already present in [DataCoverageV2]) only so every freshness-relevant date lives in one place
 * for the prompt to point to.
 */
data class SourceFreshnessV2(
    val newestActivityDate: LocalDate?,
    val fitnessSourceDate: LocalDate?,
    val fitnessAgeDays: Int?,
    val recoverySourceDate: LocalDate?,
    val recoveryAgeDays: Int?,
)

/**
 * How much evidence actually exists, as plain counts — never a quality rating (never "POOR"/"GOOD";
 * see [TrainingContextV2] provenance rule). A 90-day window with few activities must be visible as
 * exactly that: a small count against a wide window, not a dense history.
 */
data class DataCoverageV2(
    val historyWindowDays: Int,
    val supportedActivityCount: Int,
    val analysedActivityCount: Int,
    val fullSampleActivityCount: Int,
    val intervalsMatchedActivityCount: Int,
    val fitnessWindowDays: Int,
    val fitnessDaysAvailable: Int,
    val recoveryWindowDays: Int,
    val recoveryDaysAvailable: Int,
    val oldestActivityDate: LocalDate?,
    val newestActivityDate: LocalDate?,
)

/**
 * One day of Intervals.icu's fitness model, for the trend fields of [TrainingLoadContextV2]. An
 * exact stored day only — missing days are `null`, never interpolated from neighbours.
 */
data class FitnessSnapshotV2(
    val date: LocalDate,
    val ctl: Double?,
    val atl: Double?,
    val derivedForm: Double?,
)

/**
 * Intervals.icu training-model evidence (Phase 6H-5 terminology, unchanged): **CTL = Intervals'
 * calculated fitness, ATL = Intervals' calculated fatigue**, `derivedForm = ctl - atl` when both
 * exist. The subjective wellness `fatigue` field is never read anywhere upstream of this model and
 * has no field here.
 *
 * [sourceDate]/[ageDays] describe the "current" snapshot (the flat ctl/atl/derivedForm/rampRate/
 * ctlLoad/atlLoad fields below): the most recent stored Intervals fitness day at or before
 * [TrainingContextV2.date], never a later day presented as if it were today's.
 * [sevenDaysAgo]/[twentyEightDaysAgo] are exact D-7/D-28 snapshots, null when that exact day was
 * never stored (no nearest-day substitution), so the coach can read the trend direction rather than
 * one isolated number.
 */
data class TrainingLoadContextV2(
    val sourceDate: LocalDate?,
    val ageDays: Int?,
    val ctl: Double?,
    val atl: Double?,
    val derivedForm: Double?,
    val rampRate: Double?,
    val ctlLoad: Double?,
    val atlLoad: Double?,
    val sevenDaysAgo: FitnessSnapshotV2?,
    val twentyEightDaysAgo: FitnessSnapshotV2?,
)

/**
 * Factual training rhythm only — no decision, no candidate workout type (compare V1's
 * `candidateTrainingTypes`, deliberately not reproduced here). [structuredIntervalDetectionAvailable]
 * is always `true` in V2: the Running Analysis Engine can confirm an actual Garmin interval
 * structure (unlike V1, which could not and reported the detection itself as unavailable).
 * A session is "structured interval" only when the stored analysis actually has an interval group —
 * never inferred from a speed pattern.
 */
data class TrainingRhythmV2(
    val consecutiveActiveDays: Int,
    val consecutiveRestDays: Int,
    val lastRunDate: LocalDate?,
    val daysSinceLastRun: Int?,
    val lastLongRunDate: LocalDate?,
    val daysSinceLastLongRun: Int?,
    val structuredIntervalDetectionAvailable: Boolean,
    val lastStructuredIntervalDate: LocalDate?,
    val daysSinceLastStructuredInterval: Int?,
)

/** Garmin-normalized facts about one activity. Cadence/power come from the stored Garmin detail. */
data class RecentActivityFacts(
    val date: LocalDate,
    val activityType: ActivityType,
    val durationSeconds: Int,
    val distanceMeters: Double?,
    val averageHeartRateBpm: Int?,
    val maxHeartRateBpm: Int?,
    val averageCadence: Double?,
    val averagePower: Double?,
)

/**
 * One identified interval group's repeatability (Phase 6H-4), group-summary only — individual
 * repetitions are never included here (a debug/read API can expose them; the coach context stays
 * compact). [recoveryHrDropBpm]/[recoveryDurationSeconds] are RunningAI's own interval-recovery HR
 * change, not Garmin's Recovery HR metric.
 */
data class IntervalGroupEvidence(
    val workRepCount: Int,
    val meanSpeed: Double?,
    val speedCvPercent: Double?,
    val lastVsFirstSpeedChangePercent: Double?,
    val hrProgressionBpm: Double?,
    val recoveryHrDropBpm: Double?,
    val recoveryDurationSeconds: Double?,
)

/**
 * RunningAI-derived evidence for one activity (Phase 6H-4 Running Analysis), descriptive only —
 * nothing here is Garmin's or Intervals.icu's own metric, and nothing is labelled a session type
 * (e.g. never "this was a threshold workout": that is the coach's read of the LTHR exposure
 * seconds). `heartRateZonePercent` keys are zone numbers 1-5, present only when the source reported
 * that zone.
 */
data class RunningAiAnalysisEvidence(
    val hrChangePercent: Double?,
    val speedChangePercent: Double?,
    val speedHrDecouplingPercent: Double?,
    val cadenceChangePercent: Double?,
    val lthr90Seconds: Double?,
    val lthr95Seconds: Double?,
    val lthr100Seconds: Double?,
    val lapSpeedCvPercent: Double?,
    val heartRateZonePercent: Map<Int, Double>,
    val totalZoneSeconds: Double?,
    val intervalGroups: List<IntervalGroupEvidence>,
)

/** Intervals.icu per-activity training-model evidence (Phase 6H-5), never merged with Garmin's own. */
data class IntervalsActivityEvidence(
    val trainingLoad: Int?,
    val intensity: Double?,
    val ctlAfterActivity: Double?,
    val atlAfterActivity: Double?,
)

/**
 * How much the derived evidence for this activity can be trusted. `sampleCompleteness` null means no
 * sample stream was ever collected for it; `analysisStatus`/`analysisVersion` null means no stored
 * analysis exists (never recomputed here — see [com.runningai.analysis.RunningActivityAnalysisService]).
 */
data class ActivityDataQuality(
    val sampleCompleteness: SampleCompleteness?,
    val analysisStatus: AnalysisStatus?,
    val analysisVersion: String?,
)

/** One recent activity's evidence, by provenance: Garmin facts, RunningAI analysis, Intervals, quality. */
data class RecentActivityEvidence(
    val activity: RecentActivityFacts,
    val runningAiAnalysis: RunningAiAnalysisEvidence?,
    val intervals: IntervalsActivityEvidence?,
    val dataQuality: ActivityDataQuality,
)
