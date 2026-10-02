package com.runningai.activity.detail

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.runningai.activity.ExternalSource
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

// Stored forms of the detail model. Never exposed outside this package's services; callers see the
// data classes in ActivityDetailModels.kt.

@Entity
@Table(name = "activity_raw_payload")
@EntityListeners(AuditingEntityListener::class)
class ActivityRawPayloadEntity(
    @Column(name = "athlete_id", nullable = false, updatable = false)
    var athleteId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "external_source", nullable = false, length = 32, updatable = false)
    var externalSource: ExternalSource,

    @Column(name = "external_activity_id", nullable = false, length = 100, updatable = false)
    var externalActivityId: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "payload_type", nullable = false, length = 48, updatable = false)
    var payloadType: DetailPayloadType,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    var payload: JsonNode,

    @Column(name = "fetched_at", nullable = false)
    var fetchedAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        private set

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null
        private set

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null
        private set
}

interface ActivityRawPayloadRepository : JpaRepository<ActivityRawPayloadEntity, Long> {
    fun findByExternalSourceAndExternalActivityIdAndPayloadType(
        externalSource: ExternalSource,
        externalActivityId: String,
        payloadType: DetailPayloadType,
    ): ActivityRawPayloadEntity?

    fun findByExternalSourceAndExternalActivityId(
        externalSource: ExternalSource,
        externalActivityId: String,
    ): List<ActivityRawPayloadEntity>
}

@Entity
@Table(name = "activity_detail")
@EntityListeners(AuditingEntityListener::class)
class ActivityDetailEntity(
    @Column(name = "activity_id", nullable = false, updatable = false)
    var activityId: Long,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        private set

    @Enumerated(EnumType.STRING)
    @Column(name = "source_payload_type", nullable = false, length = 48)
    var sourcePayloadType: DetailPayloadType = DetailPayloadType.ACTIVITY_LIST

    @Column(name = "duration_seconds") var durationSeconds: Double? = null
    @Column(name = "elapsed_duration_seconds") var elapsedDurationSeconds: Double? = null
    @Column(name = "moving_duration_seconds") var movingDurationSeconds: Double? = null
    @Column(name = "distance_meters") var distanceMeters: Double? = null
    @Column(name = "average_speed") var averageSpeed: Double? = null
    @Column(name = "max_speed") var maxSpeed: Double? = null
    @Column(name = "average_heart_rate_bpm") var averageHeartRateBpm: Double? = null
    @Column(name = "max_heart_rate_bpm") var maxHeartRateBpm: Double? = null
    @Column(name = "average_running_cadence_spm") var averageRunningCadenceSpm: Double? = null
    @Column(name = "max_running_cadence_spm") var maxRunningCadenceSpm: Double? = null
    @Column(name = "elevation_gain") var elevationGain: Double? = null
    @Column(name = "elevation_loss") var elevationLoss: Double? = null
    @Column(name = "calories") var calories: Double? = null
    @Column(name = "average_power") var averagePower: Double? = null
    @Column(name = "max_power") var maxPower: Double? = null
    @Column(name = "normalized_power") var normalizedPower: Double? = null
    @Column(name = "aerobic_training_effect") var aerobicTrainingEffect: Double? = null
    @Column(name = "anaerobic_training_effect") var anaerobicTrainingEffect: Double? = null
    @Column(name = "training_load") var trainingLoad: Double? = null
    @Column(name = "training_effect_label", length = 64) var trainingEffectLabel: String? = null

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null
        private set

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null
        private set

    /** Replaces every metric (a metric missing from [d] becomes null: no stale value survives). */
    fun apply(d: ActivityDetailData) {
        sourcePayloadType = d.sourcePayloadType
        durationSeconds = d.durationSeconds
        elapsedDurationSeconds = d.elapsedDurationSeconds
        movingDurationSeconds = d.movingDurationSeconds
        distanceMeters = d.distanceMeters
        averageSpeed = d.averageSpeed
        maxSpeed = d.maxSpeed
        averageHeartRateBpm = d.averageHeartRateBpm
        maxHeartRateBpm = d.maxHeartRateBpm
        averageRunningCadenceSpm = d.averageRunningCadenceSpm
        maxRunningCadenceSpm = d.maxRunningCadenceSpm
        elevationGain = d.elevationGain
        elevationLoss = d.elevationLoss
        calories = d.calories
        averagePower = d.averagePower
        maxPower = d.maxPower
        normalizedPower = d.normalizedPower
        aerobicTrainingEffect = d.aerobicTrainingEffect
        anaerobicTrainingEffect = d.anaerobicTrainingEffect
        trainingLoad = d.trainingLoad
        trainingEffectLabel = d.trainingEffectLabel
    }

    fun toData() = ActivityDetailData(
        sourcePayloadType, durationSeconds, elapsedDurationSeconds, movingDurationSeconds, distanceMeters,
        averageSpeed, maxSpeed, averageHeartRateBpm, maxHeartRateBpm, averageRunningCadenceSpm,
        maxRunningCadenceSpm, elevationGain, elevationLoss, calories, averagePower, maxPower, normalizedPower,
        aerobicTrainingEffect, anaerobicTrainingEffect, trainingLoad, trainingEffectLabel,
    )
}

interface ActivityDetailRepository : JpaRepository<ActivityDetailEntity, Long> {
    fun findByActivityId(activityId: Long): ActivityDetailEntity?
}

@Entity
@Table(name = "activity_lap")
class ActivityLapEntity(
    @Column(name = "activity_id", nullable = false) var activityId: Long,
    @Column(name = "lap_index", nullable = false) var lapIndex: Int,
    @Column(name = "start_time") var startTime: Instant?,
    @Column(name = "duration_seconds") var durationSeconds: Double?,
    @Column(name = "elapsed_duration_seconds") var elapsedDurationSeconds: Double?,
    @Column(name = "moving_duration_seconds") var movingDurationSeconds: Double?,
    @Column(name = "distance_meters") var distanceMeters: Double?,
    @Column(name = "average_speed") var averageSpeed: Double?,
    @Column(name = "max_speed") var maxSpeed: Double?,
    @Column(name = "average_heart_rate_bpm") var averageHeartRateBpm: Double?,
    @Column(name = "max_heart_rate_bpm") var maxHeartRateBpm: Double?,
    @Column(name = "average_cadence") var averageCadence: Double?,
    @Column(name = "max_cadence") var maxCadence: Double?,
    @Column(name = "average_power") var averagePower: Double?,
    @Column(name = "max_power") var maxPower: Double?,
    @Column(name = "elevation_gain") var elevationGain: Double?,
    @Column(name = "elevation_loss") var elevationLoss: Double?,
    @Column(name = "calories") var calories: Double?,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "extra_metrics") var extraMetrics: JsonNode?,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        private set

    fun toData() = LapData(
        lapIndex, startTime, durationSeconds, elapsedDurationSeconds, movingDurationSeconds, distanceMeters,
        averageSpeed, maxSpeed, averageHeartRateBpm, maxHeartRateBpm, averageCadence, maxCadence, averagePower,
        maxPower, elevationGain, elevationLoss, calories, extraMetrics as ObjectNode?,
    )

    companion object {
        fun of(activityId: Long, l: LapData) = ActivityLapEntity(
            activityId, l.lapIndex, l.startTime, l.durationSeconds, l.elapsedDurationSeconds,
            l.movingDurationSeconds, l.distanceMeters, l.averageSpeed, l.maxSpeed, l.averageHeartRateBpm,
            l.maxHeartRateBpm, l.averageCadence, l.maxCadence, l.averagePower, l.maxPower, l.elevationGain,
            l.elevationLoss, l.calories, l.extraMetrics,
        )
    }
}

interface ActivityLapRepository : JpaRepository<ActivityLapEntity, Long> {
    fun findByActivityIdOrderByLapIndex(activityId: Long): List<ActivityLapEntity>

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ActivityLapEntity l where l.activityId = :activityId")
    fun deleteAllOfActivity(@Param("activityId") activityId: Long): Int
}

@Entity
@Table(name = "activity_zone")
class ActivityZoneEntity(
    @Column(name = "activity_id", nullable = false) var activityId: Long,
    @Enumerated(EnumType.STRING) @Column(name = "zone_type", nullable = false, length = 16) var zoneType: ZoneType,
    @Column(name = "zone_number", nullable = false) var zoneNumber: Int,
    @Column(name = "min_value") var minValue: Double?,
    @Column(name = "max_value") var maxValue: Double?,
    @Column(name = "duration_seconds") var durationSeconds: Double?,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        private set

    fun toData() = ZoneData(zoneType, zoneNumber, minValue, maxValue, durationSeconds)

    companion object {
        fun of(activityId: Long, z: ZoneData) =
            ActivityZoneEntity(activityId, z.zoneType, z.zoneNumber, z.minValue, z.maxValue, z.durationSeconds)
    }
}

interface ActivityZoneRepository : JpaRepository<ActivityZoneEntity, Long> {
    fun findByActivityIdAndZoneTypeOrderByZoneNumber(activityId: Long, zoneType: ZoneType): List<ActivityZoneEntity>

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ActivityZoneEntity z where z.activityId = :activityId and z.zoneType = :zoneType")
    fun deleteAllOfActivity(@Param("activityId") activityId: Long, @Param("zoneType") zoneType: ZoneType): Int
}

@Entity
@Table(name = "activity_sample")
class ActivitySampleEntity(
    @Column(name = "activity_id", nullable = false) var activityId: Long,
    @Column(name = "sample_index", nullable = false) var sampleIndex: Int,
    @Column(name = "sample_time") var sampleTime: Instant?,
    @Column(name = "elapsed_seconds") var elapsedSeconds: Double?,
    @Column(name = "distance") var distance: Double?,
    @Column(name = "speed") var speed: Double?,
    @Column(name = "heart_rate") var heartRate: Double?,
    @Column(name = "cadence") var cadence: Double?,
    @Column(name = "power") var power: Double?,
    @Column(name = "elevation") var elevation: Double?,
    @Column(name = "latitude") var latitude: Double?,
    @Column(name = "longitude") var longitude: Double?,
    @Column(name = "temperature") var temperature: Double?,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "extra_metrics") var extraMetrics: JsonNode?,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        private set

    fun toData() = SampleData(
        sampleIndex, sampleTime, elapsedSeconds, distance, speed, heartRate, cadence, power, elevation,
        latitude, longitude, temperature, extraMetrics as ObjectNode?,
    )

    companion object {
        fun of(activityId: Long, s: SampleData) = ActivitySampleEntity(
            activityId, s.sampleIndex, s.sampleTime, s.elapsedSeconds, s.distance, s.speed, s.heartRate,
            s.cadence, s.power, s.elevation, s.latitude, s.longitude, s.temperature, s.extraMetrics,
        )
    }
}

interface ActivitySampleRepository : JpaRepository<ActivitySampleEntity, Long> {
    fun findByActivityIdOrderBySampleIndex(activityId: Long): List<ActivitySampleEntity>

    fun countByActivityId(activityId: Long): Long

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ActivitySampleEntity s where s.activityId = :activityId")
    fun deleteAllOfActivity(@Param("activityId") activityId: Long): Int
}

@Entity
@Table(name = "activity_detail_collection")
class ActivityDetailCollectionEntity(
    @Column(name = "activity_id", nullable = false, updatable = false) var activityId: Long,
    @Enumerated(EnumType.STRING)
    @Column(name = "payload_type", nullable = false, length = 48, updatable = false)
    var payloadType: DetailPayloadType,
    @Enumerated(EnumType.STRING) @Column(name = "status", nullable = false, length = 24) var status: DetailPartStatus,
    @Column(name = "error_code", length = 64) var errorCode: String?,
    @Column(name = "item_count") var itemCount: Int?,
    @Column(name = "attempted_at", nullable = false) var attemptedAt: Instant,
    // Sample-stream fidelity (V14); null for every other part and whenever the stream was not stored.
    @Column(name = "requested_max_chart_size") var requestedMaxChartSize: Int? = null,
    @Column(name = "source_metrics_count") var sourceMetricsCount: Int? = null,
    @Column(name = "source_total_metrics_count") var sourceTotalMetricsCount: Int? = null,
    @Enumerated(EnumType.STRING)
    @Column(name = "sample_completeness", length = 16)
    var sampleCompleteness: SampleCompleteness? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        private set

    /** Overwrites every recorded field, so a part never keeps fidelity from an earlier collection. */
    fun apply(record: DetailPartRecord) {
        status = record.status
        errorCode = record.errorCode
        itemCount = record.itemCount
        attemptedAt = record.attemptedAt
        requestedMaxChartSize = record.sampleFidelity?.requestedMaxChartSize
        sourceMetricsCount = record.sampleFidelity?.sourceMetricsCount
        sourceTotalMetricsCount = record.sampleFidelity?.sourceTotalMetricsCount
        sampleCompleteness = record.sampleFidelity?.completeness
    }

    fun toRecord() = DetailPartRecord(
        payloadType, status, errorCode, itemCount, attemptedAt,
        sampleCompleteness?.let {
            SampleStreamFidelity(it, requestedMaxChartSize, sourceMetricsCount, sourceTotalMetricsCount)
        },
    )
}

interface ActivityDetailCollectionRepository : JpaRepository<ActivityDetailCollectionEntity, Long> {
    fun findByActivityIdAndPayloadType(activityId: Long, payloadType: DetailPayloadType): ActivityDetailCollectionEntity?

    fun findByActivityId(activityId: Long): List<ActivityDetailCollectionEntity>
}
