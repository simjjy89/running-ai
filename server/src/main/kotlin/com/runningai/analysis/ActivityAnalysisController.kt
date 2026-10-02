package com.runningai.analysis

import com.runningai.activity.detail.SampleCompleteness
import com.runningai.common.exception.ResourceNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
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
    val status: AnalysisStatus,
    val inputSampleCompleteness: SampleCompleteness?,
    val computedAt: Instant,
    val session: SessionResponse,
    val heartRateZones: ZoneResponse,
    val thresholdExposure: ThresholdExposure,
    val laps: LapMetrics,
    val intervalGroups: List<IntervalGroupResponse>,
) {
    companion object {
        fun of(a: ActivityAnalysis) = ActivityAnalysisResponse(
            activityId = a.activityId,
            analysisVersion = a.analysisVersion,
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
