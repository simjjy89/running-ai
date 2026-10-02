package com.runningai.analysis

import com.runningai.activity.detail.SampleCompleteness
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

// Stored form of the derived analysis. Callers see the data classes in AnalysisModels.kt.

@Entity
@Table(name = "activity_analysis")
class ActivityAnalysisEntity(
    @Column(name = "activity_id", nullable = false, updatable = false) var activityId: Long,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        private set

    @Column(name = "analysis_version", nullable = false, length = 32) var analysisVersion: String = RUNNING_ANALYSIS_VERSION

    @Enumerated(EnumType.STRING)
    @Column(name = "analysis_status", nullable = false, length = 24)
    var analysisStatus: AnalysisStatus = AnalysisStatus.INSUFFICIENT_DATA

    @Enumerated(EnumType.STRING)
    @Column(name = "input_sample_completeness", length = 16)
    var inputSampleCompleteness: SampleCompleteness? = null

    @Column(name = "computed_at", nullable = false) var computedAt: Instant = Instant.EPOCH

    @Column(name = "valid_sample_count") var validSampleCount: Int? = null
    @Column(name = "analysis_duration_seconds") var analysisDurationSeconds: Double? = null
    @Column(name = "analysis_distance_meters") var analysisDistanceMeters: Double? = null

    @Column(name = "first_half_avg_hr") var firstHalfAvgHr: Double? = null
    @Column(name = "second_half_avg_hr") var secondHalfAvgHr: Double? = null
    @Column(name = "hr_change_bpm") var hrChangeBpm: Double? = null
    @Column(name = "hr_change_percent") var hrChangePercent: Double? = null
    @Column(name = "first_half_avg_speed") var firstHalfAvgSpeed: Double? = null
    @Column(name = "second_half_avg_speed") var secondHalfAvgSpeed: Double? = null
    @Column(name = "speed_change_percent") var speedChangePercent: Double? = null
    @Column(name = "first_half_avg_cadence") var firstHalfAvgCadence: Double? = null
    @Column(name = "second_half_avg_cadence") var secondHalfAvgCadence: Double? = null
    @Column(name = "cadence_change_spm") var cadenceChangeSpm: Double? = null
    @Column(name = "cadence_change_percent") var cadenceChangePercent: Double? = null
    @Column(name = "first_half_speed_hr_ratio") var firstHalfSpeedHrRatio: Double? = null
    @Column(name = "second_half_speed_hr_ratio") var secondHalfSpeedHrRatio: Double? = null
    @Column(name = "speed_hr_decoupling_percent") var speedHrDecouplingPercent: Double? = null

    @Column(name = "hr_zone1_seconds") var hrZone1Seconds: Double? = null
    @Column(name = "hr_zone2_seconds") var hrZone2Seconds: Double? = null
    @Column(name = "hr_zone3_seconds") var hrZone3Seconds: Double? = null
    @Column(name = "hr_zone4_seconds") var hrZone4Seconds: Double? = null
    @Column(name = "hr_zone5_seconds") var hrZone5Seconds: Double? = null
    @Column(name = "hr_zone_total_seconds") var hrZoneTotalSeconds: Double? = null
    @Column(name = "hr_zone1_percent") var hrZone1Percent: Double? = null
    @Column(name = "hr_zone2_percent") var hrZone2Percent: Double? = null
    @Column(name = "hr_zone3_percent") var hrZone3Percent: Double? = null
    @Column(name = "hr_zone4_percent") var hrZone4Percent: Double? = null
    @Column(name = "hr_zone5_percent") var hrZone5Percent: Double? = null

    @Column(name = "lthr90_seconds") var lthr90Seconds: Double? = null
    @Column(name = "lthr95_seconds") var lthr95Seconds: Double? = null
    @Column(name = "lthr100_seconds") var lthr100Seconds: Double? = null

    @Column(name = "lap_count") var lapCount: Int? = null
    @Column(name = "lap_speed_mean") var lapSpeedMean: Double? = null
    @Column(name = "lap_speed_stddev") var lapSpeedStdDev: Double? = null
    @Column(name = "lap_speed_cv_percent") var lapSpeedCvPercent: Double? = null
    @Column(name = "lap_hr_mean") var lapHrMean: Double? = null
    @Column(name = "lap_hr_progression") var lapHrProgression: Double? = null
    @Column(name = "lap_cadence_mean") var lapCadenceMean: Double? = null

    /** Replaces every derived value, so nothing from an earlier analysis survives a re-analysis. */
    fun apply(a: ActivityAnalysis) {
        analysisVersion = a.analysisVersion
        analysisStatus = a.status
        inputSampleCompleteness = a.inputSampleCompleteness
        computedAt = a.computedAt
        validSampleCount = a.session.validSampleCount
        analysisDurationSeconds = a.session.analysisDurationSeconds
        analysisDistanceMeters = a.session.analysisDistanceMeters
        val halves = a.session.halves
        firstHalfAvgHr = halves.firstHalfAvgHr
        secondHalfAvgHr = halves.secondHalfAvgHr
        hrChangeBpm = halves.hrChangeBpm
        hrChangePercent = halves.hrChangePercent
        firstHalfAvgSpeed = halves.firstHalfAvgSpeed
        secondHalfAvgSpeed = halves.secondHalfAvgSpeed
        speedChangePercent = halves.speedChangePercent
        firstHalfAvgCadence = halves.firstHalfAvgCadence
        secondHalfAvgCadence = halves.secondHalfAvgCadence
        cadenceChangeSpm = halves.cadenceChangeSpm
        cadenceChangePercent = halves.cadenceChangePercent
        firstHalfSpeedHrRatio = halves.firstHalfSpeedHrRatio
        secondHalfSpeedHrRatio = halves.secondHalfSpeedHrRatio
        speedHrDecouplingPercent = halves.speedHrDecouplingPercent
        hrZone1Seconds = a.zones.zoneSeconds[1]
        hrZone2Seconds = a.zones.zoneSeconds[2]
        hrZone3Seconds = a.zones.zoneSeconds[3]
        hrZone4Seconds = a.zones.zoneSeconds[4]
        hrZone5Seconds = a.zones.zoneSeconds[5]
        hrZoneTotalSeconds = a.zones.totalSeconds
        hrZone1Percent = a.zones.zonePercent[1]
        hrZone2Percent = a.zones.zonePercent[2]
        hrZone3Percent = a.zones.zonePercent[3]
        hrZone4Percent = a.zones.zonePercent[4]
        hrZone5Percent = a.zones.zonePercent[5]
        lthr90Seconds = a.threshold.lthr90Seconds
        lthr95Seconds = a.threshold.lthr95Seconds
        lthr100Seconds = a.threshold.lthr100Seconds
        lapCount = a.laps.lapCount
        lapSpeedMean = a.laps.speedMean
        lapSpeedStdDev = a.laps.speedStdDev
        lapSpeedCvPercent = a.laps.speedCvPercent
        lapHrMean = a.laps.hrMean
        lapHrProgression = a.laps.hrProgression
        lapCadenceMean = a.laps.cadenceMean
    }

    fun toData(groups: List<IntervalGroup>) = ActivityAnalysis(
        activityId = activityId,
        analysisVersion = analysisVersion,
        status = analysisStatus,
        inputSampleCompleteness = inputSampleCompleteness,
        computedAt = computedAt,
        session = SessionMetrics(
            validSampleCount = validSampleCount ?: 0,
            analysisDurationSeconds = analysisDurationSeconds,
            analysisDistanceMeters = analysisDistanceMeters,
            halves = HalfSplitMetrics(
                firstHalfAvgHr, secondHalfAvgHr, hrChangeBpm, hrChangePercent,
                firstHalfAvgSpeed, secondHalfAvgSpeed, speedChangePercent,
                firstHalfAvgCadence, secondHalfAvgCadence, cadenceChangeSpm, cadenceChangePercent,
                firstHalfSpeedHrRatio, secondHalfSpeedHrRatio, speedHrDecouplingPercent,
            ),
        ),
        zones = ZoneExposure(
            zoneSeconds = listOfNotNull(
                hrZone1Seconds?.let { 1 to it }, hrZone2Seconds?.let { 2 to it }, hrZone3Seconds?.let { 3 to it },
                hrZone4Seconds?.let { 4 to it }, hrZone5Seconds?.let { 5 to it },
            ).toMap(),
            totalSeconds = hrZoneTotalSeconds,
            zonePercent = listOfNotNull(
                hrZone1Percent?.let { 1 to it }, hrZone2Percent?.let { 2 to it }, hrZone3Percent?.let { 3 to it },
                hrZone4Percent?.let { 4 to it }, hrZone5Percent?.let { 5 to it },
            ).toMap(),
        ),
        threshold = ThresholdExposure(lthr90Seconds, lthr95Seconds, lthr100Seconds),
        laps = LapMetrics(
            lapCount ?: 0, lapSpeedMean, lapSpeedStdDev, lapSpeedCvPercent, lapHrMean, lapHrProgression, lapCadenceMean,
        ),
        intervalGroups = groups,
    )
}

interface ActivityAnalysisRepository : JpaRepository<ActivityAnalysisEntity, Long> {
    fun findByActivityId(activityId: Long): ActivityAnalysisEntity?
}

@Entity
@Table(name = "activity_analysis_interval_group")
class ActivityAnalysisIntervalGroupEntity(
    @Column(name = "activity_analysis_id", nullable = false) var activityAnalysisId: Long,
    @Column(name = "group_index", nullable = false) var groupIndex: Int,
    @Column(name = "workout_step_index") var workoutStepIndex: Int?,
    @Column(name = "work_rep_count", nullable = false) var workRepCount: Int,
    @Column(name = "mean_speed") var meanSpeed: Double?,
    @Column(name = "speed_stddev") var speedStdDev: Double?,
    @Column(name = "speed_cv_percent") var speedCvPercent: Double?,
    @Column(name = "first_rep_speed") var firstRepSpeed: Double?,
    @Column(name = "last_rep_speed") var lastRepSpeed: Double?,
    @Column(name = "last_vs_first_speed_change_percent") var lastVsFirstSpeedChangePercent: Double?,
    @Column(name = "first_rep_hr") var firstRepHr: Double?,
    @Column(name = "last_rep_hr") var lastRepHr: Double?,
    @Column(name = "hr_progression_bpm") var hrProgressionBpm: Double?,
    @Column(name = "recovery_start_hr") var recoveryStartHr: Double?,
    @Column(name = "recovery_end_hr") var recoveryEndHr: Double?,
    @Column(name = "recovery_hr_drop_bpm") var recoveryHrDropBpm: Double?,
    @Column(name = "recovery_duration_seconds") var recoveryDurationSeconds: Double?,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        private set

    companion object {
        fun of(analysisId: Long, g: IntervalGroup) = ActivityAnalysisIntervalGroupEntity(
            analysisId, g.groupIndex, g.workoutStepIndex, g.workRepCount, g.meanSpeed, g.speedStdDev,
            g.speedCvPercent, g.firstRepSpeed, g.lastRepSpeed, g.lastVsFirstSpeedChangePercent,
            g.firstRepHr, g.lastRepHr, g.hrProgressionBpm,
            g.recovery.startHr, g.recovery.endHr, g.recovery.dropBpm, g.recovery.durationSeconds,
        )
    }
}

interface ActivityAnalysisIntervalGroupRepository : JpaRepository<ActivityAnalysisIntervalGroupEntity, Long> {
    fun findByActivityAnalysisIdOrderByGroupIndex(activityAnalysisId: Long): List<ActivityAnalysisIntervalGroupEntity>

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ActivityAnalysisIntervalGroupEntity g where g.activityAnalysisId = :id")
    fun deleteAllOfAnalysis(@Param("id") id: Long): Int
}

@Entity
@Table(name = "activity_analysis_interval")
class ActivityAnalysisIntervalEntity(
    @Column(name = "activity_analysis_id", nullable = false) var activityAnalysisId: Long,
    @Column(name = "group_index", nullable = false) var groupIndex: Int,
    @Column(name = "repetition_index", nullable = false) var repetitionIndex: Int,
    @Column(name = "workout_step_index") var workoutStepIndex: Int?,
    @Column(name = "first_lap_index") var firstLapIndex: Int?,
    @Column(name = "last_lap_index") var lastLapIndex: Int?,
    @Column(name = "duration_seconds") var durationSeconds: Double?,
    @Column(name = "distance_meters") var distanceMeters: Double?,
    @Column(name = "average_speed") var averageSpeed: Double?,
    @Column(name = "average_hr") var averageHr: Double?,
    @Column(name = "max_hr") var maxHr: Double?,
    @Column(name = "average_cadence") var averageCadence: Double?,
    @Column(name = "average_power") var averagePower: Double?,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        private set

    fun toData() = IntervalRepetition(
        repetitionIndex, workoutStepIndex, firstLapIndex ?: 0, lastLapIndex ?: 0, durationSeconds,
        distanceMeters, averageSpeed, averageHr, maxHr, averageCadence, averagePower,
    )

    companion object {
        fun of(analysisId: Long, groupIndex: Int, r: IntervalRepetition) = ActivityAnalysisIntervalEntity(
            analysisId, groupIndex, r.repetitionIndex, r.workoutStepIndex, r.firstLapIndex, r.lastLapIndex,
            r.durationSeconds, r.distanceMeters, r.averageSpeed, r.averageHr, r.maxHr, r.averageCadence,
            r.averagePower,
        )
    }
}

interface ActivityAnalysisIntervalRepository : JpaRepository<ActivityAnalysisIntervalEntity, Long> {
    fun findByActivityAnalysisIdOrderByGroupIndexAscRepetitionIndexAsc(
        activityAnalysisId: Long,
    ): List<ActivityAnalysisIntervalEntity>

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ActivityAnalysisIntervalEntity i where i.activityAnalysisId = :id")
    fun deleteAllOfAnalysis(@Param("id") id: Long): Int
}
