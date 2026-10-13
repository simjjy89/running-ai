package com.runningai.analysis

import com.runningai.activity.ActivityRepository
import com.runningai.activity.ExternalSource
import com.runningai.activity.detail.SampleCompleteness
import com.runningai.common.exception.ResourceNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * Manual analysis of one stored activity (Phase 6H-4).
 *
 * `POST` recomputes from the stored data and replaces the derived rows; `GET` reads the last result.
 * Neither reaches Garmin or Intervals.icu, and nothing here can publish a workout. No authentication,
 * same convention as the other operational APIs: private network only.
 */
@RestController
@RequestMapping("/api/v1/activities/{activityId:[0-9]+}/analysis")
class ActivityAnalysisController(private val service: RunningActivityAnalysisService) {

    @PostMapping
    fun analyse(@PathVariable activityId: Long): ActivityAnalysisResponse =
        ActivityAnalysisResponse.of(service.analyse(activityId))

    @GetMapping
    fun get(@PathVariable activityId: Long): ResponseEntity<ActivityAnalysisResponse> {
        val analysis = service.find(activityId)
            ?: throw ResourceNotFoundException(
                "ACTIVITY_ANALYSIS_NOT_FOUND",
                "Activity $activityId has not been analysed; POST to this path first",
            )
        return ResponseEntity.status(HttpStatus.OK).body(ActivityAnalysisResponse.of(analysis))
    }
}

/** Read model. Every metric is nullable: absent means the stored data did not support it. */
data class ActivityAnalysisResponse(
    val activityId: Long,
    val analysisVersion: String,
    /** Which engine produced these numbers (Phase 7E-1); 0 = stored before revisions were tracked. */
    val analysisEngineRevision: Int,
    val status: AnalysisStatus,
    val inputSampleCompleteness: SampleCompleteness?,
    val computedAt: Instant,
    val session: SessionResponse,
    val heartRateZones: ZoneResponse,
    val thresholdExposure: ThresholdExposure,
    val laps: LapMetrics,
    val runningDynamics: RunningDynamicsMetrics,
    val intervalGroups: List<IntervalGroupResponse>,
) {
    companion object {
        fun of(a: ActivityAnalysis) = ActivityAnalysisResponse(
            activityId = a.activityId,
            analysisVersion = a.analysisVersion,
            analysisEngineRevision = a.analysisEngineRevision,
            status = a.status,
            inputSampleCompleteness = a.inputSampleCompleteness,
            computedAt = a.computedAt,
            session = SessionResponse(
                validSampleCount = a.session.validSampleCount,
                analysisDurationSeconds = a.session.analysisDurationSeconds,
                analysisDistanceMeters = a.session.analysisDistanceMeters,
                halves = a.session.halves,
            ),
            heartRateZones = ZoneResponse(
                secondsByZone = a.zones.zoneSeconds,
                percentByZone = a.zones.zonePercent,
                totalSeconds = a.zones.totalSeconds,
            ),
            thresholdExposure = a.threshold,
            laps = a.laps,
            runningDynamics = a.runningDynamics,
            intervalGroups = a.intervalGroups.map { IntervalGroupResponse.of(it) },
        )
    }
}

data class SessionResponse(
    val validSampleCount: Int,
    val analysisDurationSeconds: Double?,
    val analysisDistanceMeters: Double?,
    val halves: HalfSplitMetrics,
)

data class ZoneResponse(
    val secondsByZone: Map<Int, Double>,
    val percentByZone: Map<Int, Double>,
    val totalSeconds: Double?,
)

data class IntervalGroupResponse(
    val groupIndex: Int,
    val workoutStepIndex: Int?,
    val workRepCount: Int,
    val meanSpeed: Double?,
    val speedStdDev: Double?,
    val speedCvPercent: Double?,
    val firstRepSpeed: Double?,
    val lastRepSpeed: Double?,
    val lastVsFirstSpeedChangePercent: Double?,
    val firstRepHr: Double?,
    val lastRepHr: Double?,
    val hrProgressionBpm: Double?,
    /** RunningAI interval recovery HR change, not Garmin's Recovery HR metric. */
    val recovery: RecoveryHrChange,
    val repetitions: List<IntervalRepetition>,
) {
    companion object {
        fun of(g: IntervalGroup) = IntervalGroupResponse(
            g.groupIndex, g.workoutStepIndex, g.workRepCount, g.meanSpeed, g.speedStdDev, g.speedCvPercent,
            g.firstRepSpeed, g.lastRepSpeed, g.lastVsFirstSpeedChangePercent, g.firstRepHr, g.lastRepHr,
            g.hrProgressionBpm, g.recovery, g.repetitions,
        )
    }
}

/**
 * Re-analysis sweep over the stored activities (Phase 7E-1).
 *
 * Exists because the defect Phase 7E-0 found is invisible per activity: the only way to see that some
 * stored analyses were produced by an older engine is to ask every one of them. The sweep asks
 * [RunningActivityAnalysisService.reuseDecision] - the same policy a single refresh uses - so a dry run
 * can never disagree with the run it previews.
 *
 * DB-only: no Garmin call, no Intervals call, nothing published. An activity whose recomputation fails
 * is reported as failed and keeps its previously stored analysis; the sweep continues with the next one,
 * because one unanalysable activity must not block repairing the rest.
 */
@RestController
@RequestMapping("/api/v1/activities/analysis-refresh")
class ActivityAnalysisRefreshController(
    private val service: RunningActivityAnalysisService,
    private val activities: ActivityRepository,
) {

    private val log = LoggerFactory.getLogger(ActivityAnalysisRefreshController::class.java)

    @PostMapping
    fun refresh(@RequestParam(defaultValue = "false") dryRun: Boolean): AnalysisRefreshSummary {
        val results = activities.findAllByOrderByStartedAtDesc().map { activity ->
            val activityId = activity.id
            when (val decision = service.reuseDecision(activityId)) {
                is AnalysisReuseDecision.Reuse ->
                    AnalysisRefreshItem(activityId, activity.externalSource, activity.externalId, true, null, null, null)
                is AnalysisReuseDecision.Reanalyse -> {
                    if (dryRun) {
                        AnalysisRefreshItem(activityId, activity.externalSource, activity.externalId,
                            false, decision.reason, null, null)
                    } else {
                        try {
                            val analysis = service.analyse(activityId)
                            AnalysisRefreshItem(activityId, activity.externalSource, activity.externalId,
                                false, decision.reason, analysis.status, null)
                        } catch (e: Exception) {
                            // The stored analysis is untouched: analyse() replaces in one transaction and
                            // only after every value exists. Reported, never hidden, never retried here.
                            log.warn("Analysis refresh failed: activityId={} error={}", activityId, e.javaClass.simpleName)
                            AnalysisRefreshItem(activityId, activity.externalSource, activity.externalId,
                                false, decision.reason, null, e.javaClass.simpleName)
                        }
                    }
                }
            }
        }
        return AnalysisRefreshSummary(
            dryRun = dryRun,
            contract = RUNNING_ANALYSIS_VERSION,
            engineRevision = RUNNING_ANALYSIS_ENGINE_REVISION,
            examined = results.size,
            reused = results.count { it.reused },
            reanalysed = results.count { !it.reused && it.failure == null && !dryRun },
            pending = results.count { !it.reused && dryRun },
            failed = results.count { it.failure != null },
            activities = results,
        )
    }
}

data class AnalysisRefreshItem(
    val activityId: Long,
    val externalSource: ExternalSource,
    val externalId: String,
    val reused: Boolean,
    val reanalysisReason: ReanalysisReason?,
    val status: AnalysisStatus?,
    val failure: String?,
)

data class AnalysisRefreshSummary(
    val dryRun: Boolean,
    val contract: String,
    val engineRevision: Int,
    val examined: Int,
    val reused: Int,
    val reanalysed: Int,
    val pending: Int,
    val failed: Int,
    val activities: List<AnalysisRefreshItem>,
)
