package com.runningai.enrichment

import com.fasterxml.jackson.databind.JsonNode
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
import java.time.Instant
import java.time.LocalDate

// Stored forms (V17/V18). Never exposed outside the enrichment services; callers see the data classes
// in IntervalsEnrichmentModels.kt.

@Entity
@Table(name = "activity_source_link")
@EntityListeners(AuditingEntityListener::class)
class ActivitySourceLinkEntity(
    @Column(name = "activity_id", nullable = false, updatable = false)
    var activityId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "external_source", nullable = false, length = 32, updatable = false)
    var externalSource: ExternalSource,

    @Column(name = "external_activity_id", nullable = false, length = 100)
    var externalActivityId: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "match_method", nullable = false, length = 24)
    var matchMethod: MatchMethod,

    @Column(name = "start_time_delta_seconds") var startTimeDeltaSeconds: Int?,
    @Column(name = "duration_delta_seconds") var durationDeltaSeconds: Int?,
    @Column(name = "distance_delta_meters") var distanceDeltaMeters: Double?,

    @Column(name = "matched_at", nullable = false)
    var matchedAt: Instant,
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

    fun toData() = ActivitySourceLinkData(
        activityId, externalSource, externalActivityId, matchMethod,
        MatchEvidence(startTimeDeltaSeconds, durationDeltaSeconds, distanceDeltaMeters), matchedAt,
    )
}

interface ActivitySourceLinkRepository : JpaRepository<ActivitySourceLinkEntity, Long> {
    fun findByActivityIdAndExternalSource(activityId: Long, externalSource: ExternalSource): ActivitySourceLinkEntity?

    fun findByExternalSourceAndExternalActivityId(
        externalSource: ExternalSource,
        externalActivityId: String,
    ): ActivitySourceLinkEntity?
}

@Entity
@Table(name = "intervals_raw_payload")
@EntityListeners(AuditingEntityListener::class)
class IntervalsRawPayloadEntity(
    @Column(name = "athlete_id", nullable = false, updatable = false)
    var athleteId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "payload_type", nullable = false, length = 32, updatable = false)
    var payloadType: IntervalsPayloadType,

    @Column(name = "external_id", nullable = false, length = 100, updatable = false)
    var externalId: String,

    @Column(name = "effective_date") var effectiveDate: LocalDate?,

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

interface IntervalsRawPayloadRepository : JpaRepository<IntervalsRawPayloadEntity, Long> {
    fun findByAthleteIdAndPayloadTypeAndExternalId(
        athleteId: Long,
        payloadType: IntervalsPayloadType,
        externalId: String,
    ): IntervalsRawPayloadEntity?
}

@Entity
@Table(name = "activity_intervals_metrics")
@EntityListeners(AuditingEntityListener::class)
class ActivityIntervalsMetricsEntity(
    @Column(name = "activity_id", nullable = false, updatable = false)
    var activityId: Long,

    @Column(name = "intervals_activity_id", nullable = false, length = 100)
    var intervalsActivityId: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        private set

    @Column(name = "training_load") var trainingLoad: Int? = null
    @Column(name = "intensity") var intensity: Double? = null
    @Column(name = "ctl_after_activity") var ctlAfterActivity: Double? = null
    @Column(name = "atl_after_activity") var atlAfterActivity: Double? = null
    @Column(name = "source_updated_at") var sourceUpdatedAt: Instant? = null

    @Column(name = "fetched_at", nullable = false)
    var fetchedAt: Instant = Instant.EPOCH

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null
        private set

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null
        private set

    /** Replaces every metric: a value the source no longer carries becomes null, never stale. */
    fun apply(snapshot: IntervalsActivitySnapshot, fetchedAt: Instant) {
        intervalsActivityId = snapshot.intervalsActivityId
        trainingLoad = snapshot.trainingLoad
        intensity = snapshot.intensity
        ctlAfterActivity = snapshot.ctlAfterActivity
        atlAfterActivity = snapshot.atlAfterActivity
        sourceUpdatedAt = snapshot.sourceUpdatedAt
        this.fetchedAt = fetchedAt
    }

    fun toData() = ActivityIntervalsMetricsData(
        activityId, intervalsActivityId, trainingLoad, intensity, ctlAfterActivity, atlAfterActivity,
        sourceUpdatedAt, fetchedAt,
    )
}

interface ActivityIntervalsMetricsRepository : JpaRepository<ActivityIntervalsMetricsEntity, Long> {
    fun findByActivityId(activityId: Long): ActivityIntervalsMetricsEntity?
}

@Entity
@Table(name = "intervals_fitness_daily")
@EntityListeners(AuditingEntityListener::class)
class IntervalsFitnessDailyEntity(
    @Column(name = "athlete_id", nullable = false, updatable = false)
    var athleteId: Long,

    @Column(name = "fitness_date", nullable = false, updatable = false)
    var fitnessDate: LocalDate,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        private set

    @Column(name = "ctl") var ctl: Double? = null
    @Column(name = "atl") var atl: Double? = null
    @Column(name = "derived_form") var derivedForm: Double? = null
    @Column(name = "ramp_rate") var rampRate: Double? = null
    @Column(name = "ctl_load") var ctlLoad: Double? = null
    @Column(name = "atl_load") var atlLoad: Double? = null
    @Column(name = "source_updated_at") var sourceUpdatedAt: Instant? = null

    @Column(name = "fetched_at", nullable = false)
    var fetchedAt: Instant = Instant.EPOCH

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null
        private set

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null
        private set

    fun apply(day: IntervalsWellnessDay, fetchedAt: Instant) {
        ctl = day.ctl
        atl = day.atl
        derivedForm = day.derivedForm
        rampRate = day.rampRate
        ctlLoad = day.ctlLoad
        atlLoad = day.atlLoad
        sourceUpdatedAt = day.sourceUpdatedAt
        this.fetchedAt = fetchedAt
    }

    fun toData() = IntervalsFitnessDayData(
        fitnessDate, ctl, atl, derivedForm, rampRate, ctlLoad, atlLoad, sourceUpdatedAt, fetchedAt,
    )
}

interface IntervalsFitnessDailyRepository : JpaRepository<IntervalsFitnessDailyEntity, Long> {
    fun findByAthleteIdAndFitnessDate(athleteId: Long, fitnessDate: LocalDate): IntervalsFitnessDailyEntity?

    fun findByAthleteIdAndFitnessDateBetweenOrderByFitnessDate(
        athleteId: Long,
        oldest: LocalDate,
        newest: LocalDate,
    ): List<IntervalsFitnessDailyEntity>
}
