package com.runningai.analysis

import com.runningai.activity.Activity
import com.runningai.activity.ActivityRepository
import com.runningai.activity.ActivityType
import com.runningai.activity.detail.ActivityDetailStore
import com.runningai.activity.detail.DetailPayloadType
import com.runningai.activity.detail.ZoneType
import com.runningai.athlete.AthleteIntensityProfileRepository
import com.runningai.common.exception.ResourceNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

/**
 * Derives objective evidence from one already-stored activity (Phase 6H-4).
 *
 * **Reads stored data only.** No Garmin call, no Intervals call, nothing published: the inputs are
 * `activity`, `activity_lap`, `activity_zone`, `activity_sample`, the recorded sample fidelity and the
 * athlete's LTHR. If the stored detail is stale, the fix is to re-collect it, not to fetch from here.
 *
 * **It measures, it does not judge.** Nothing in this package rates a session, scores readiness or risk,
 * or labels anything good or bad. That reading is the AI coach's, and keeping it out of here is what lets
 * the coach be given evidence rather than a verdict it would have to trust blindly.
 */
/**
 * One activity after a refresh pass: the analysis that now stands, whether it was reused unchanged and,
 * when it was not, why it had to be recomputed.
 */
data class AnalysisRefreshOutcome(
    val activityId: Long,
    val analysis: ActivityAnalysis,
    val reused: Boolean,
    val reanalysisReason: ReanalysisReason?,
)

@Service
class RunningActivityAnalysisService(
    private val activities: ActivityRepository,
    private val detail: ActivityDetailStore,
    private val profiles: AthleteIntensityProfileRepository,
    private val sessionMetrics: SessionMetricsCalculator,
    private val zoneExposure: ZoneExposureCalculator,
    private val thresholdExposure: ThresholdExposureCalculator,
    private val lapMetrics: LapMetricsCalculator,
    private val runningDynamics: RunningDynamicsCalculator,
    private val extractor: IntervalStructureExtractor,
    private val intervalMetrics: IntervalMetricsCalculator,
    private val store: ActivityAnalysisStore,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(RunningActivityAnalysisService::class.java)

    /**
     * Recomputes the analysis of one activity only when the stored one may not be reused
     * (Phase 7E-1). The decision - and why - comes from [AnalysisReusePolicy]; this method never
     * second-guesses it, so a future engine change reaches every caller through the one policy.
     *
     * A failed recomputation leaves the previously stored analysis exactly as it was: nothing is
     * deleted before the new values exist, and the replacement is a single transaction.
     */
    fun analyseIfNeeded(activityId: Long): AnalysisRefreshOutcome {
        val activity = requireActivity(activityId)
        val digest = inputDigestOf(activity)
        val stored = store.find(activityId)
        return when (val decision = AnalysisReusePolicy.decide(stored, digest)) {
            is AnalysisReuseDecision.Reuse -> AnalysisRefreshOutcome(activityId, requireNotNull(stored), true, null)
            is AnalysisReuseDecision.Reanalyse ->
                AnalysisRefreshOutcome(activityId, analyse(activityId), false, decision.reason)
        }
    }

    /** Recomputes and replaces the analysis of one activity. */
    fun analyse(activityId: Long): ActivityAnalysis {
        val activity = requireActivity(activityId)

        val samples = detail.samples(activityId)
        val laps = detail.laps(activityId)
        val zones = detail.zones(activityId, ZoneType.HEART_RATE)
        val completeness = detail.part(activityId, DetailPayloadType.ACTIVITY_DETAILS_STREAM)
            ?.sampleFidelity?.completeness

        // Running cadence is a running measurement; a bike's cadence is a different thing with the same
        // name, so it is not folded into a "cadence change" for anything but a run.
        val isRun = activity.activityType in RUNNING_TYPES

        val session = sessionMetrics.calculate(samples, includeCadence = isRun)
        val timeline = sessionMetrics.timeline(samples)
        val blocks = extractor.blocks(laps)
        val groups = intervalMetrics.enrich(
            extractor.groups(blocks), blocks, timeline, timeline?.let { samples.first().sampleTime },
        )

        val analysis = ActivityAnalysis(
            activityId = activityId,
            analysisVersion = RUNNING_ANALYSIS_VERSION,
            analysisEngineRevision = RUNNING_ANALYSIS_ENGINE_REVISION,
            inputDigest = inputDigestOf(activity),
            status = status(session, laps.size, zones.size),
            inputSampleCompleteness = completeness,
            computedAt = Instant.now(clock),
            session = session,
            zones = zoneExposure.calculate(zones),
            threshold = thresholdExposure.calculate(samples, lactateThresholdHrOf(activity.athleteId)),
            laps = lapMetrics.calculate(laps),
            runningDynamics = runningDynamics.calculate(laps, isRun),
            intervalGroups = groups,
        )
        store.replace(analysis)
        log.info(
            "Activity analysis computed: activityId={} status={} version={} revision={} samples={} intervalGroups={}",
            activityId, analysis.status, analysis.analysisVersion, analysis.analysisEngineRevision,
            session.validSampleCount, groups.size,
        )
        return analysis
    }

    fun find(activityId: Long): ActivityAnalysis? = store.find(activityId)

    /**
     * What a refresh would do for [activityId], without doing it. DB-only, and the same policy the
     * refresh itself uses, so a dry run can never disagree with the run it previews.
     */
    fun reuseDecision(activityId: Long): AnalysisReuseDecision {
        val activity = requireActivity(activityId)
        return AnalysisReusePolicy.decide(store.find(activityId), inputDigestOf(activity))
    }

    /**
     * Digest of what this service reads for [activity] - see [AnalysisInputFingerprint]. Derived from
     * the recorded collection parts rather than the sample rows, so asking the question stays cheap.
     */
    private fun inputDigestOf(activity: Activity): String =
        AnalysisInputFingerprint.of(activity, detail.collection(activity.id), lactateThresholdHrOf(activity.athleteId))

    private fun requireActivity(activityId: Long): Activity = activities.findById(activityId).orElseThrow {
        ResourceNotFoundException("ACTIVITY_NOT_FOUND", "No activity $activityId")
    }

    private fun lactateThresholdHrOf(athleteId: Long): Int? =
        profiles.findByAthleteId(athleteId).orElse(null)?.lactateThresholdHeartRateBpm

    /**
     * Whether the stored data could be analysed - never a verdict on the session.
     *
     * INSUFFICIENT_DATA when there is nothing to work from at all; PARTIAL when samples exist but the
     * halves could not be split (no usable time base), because the half metrics are the bulk of what a
     * caller asks for; COMPLETE otherwise. Laps and zones alone still beat having nothing.
     */
    private fun status(session: SessionMetrics, lapCount: Int, zoneCount: Int): AnalysisStatus = when {
        session.validSampleCount == 0 && lapCount == 0 && zoneCount == 0 -> AnalysisStatus.INSUFFICIENT_DATA
        session.validSampleCount == 0 -> AnalysisStatus.PARTIAL
        session.halves.firstHalfAvgHr == null && session.halves.firstHalfAvgSpeed == null -> AnalysisStatus.PARTIAL
        else -> AnalysisStatus.COMPLETE
    }

    private companion object {
        val RUNNING_TYPES = setOf(ActivityType.RUN, ActivityType.TREADMILL_RUN)
    }
}
