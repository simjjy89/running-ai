package com.runningai.enrichment

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

/**
 * What the Intervals-only activity importer decided about one Intervals.icu activity (Phase 7E-1, V25).
 *
 * See [IntervalsImportOutcome] for the vocabulary. The row is the importer's idempotency key, its resume
 * point, its progress log and its data-quality record at once - which is why it is written for every
 * candidate, including the ones that were deliberately not imported.
 */
enum class IntervalsImportOutcome {
    /** A new `activity` row was created from Intervals data. */
    IMPORTED,

    /** An already-imported Intervals activity was re-imported because the source had changed. */
    REFRESHED,

    /** Unchanged since the last import; nothing was written. */
    SKIPPED_ALREADY_IMPORTED,

    /** The same workout already existed from another source; the Intervals identity was attached to it. */
    LINKED_EXISTING,

    /** That link already existed. */
    SKIPPED_ALREADY_LINKED,

    /** The payload names Garmin as its upstream: the 6H-5 enrichment path owns it, not this importer. */
    SKIPPED_GARMIN_SOURCED,

    /** Two or more stored activities could be this workout. Never merged automatically. */
    SKIPPED_AMBIGUOUS,

    /** No [com.runningai.activity.ActivityType] models this Intervals type. */
    SKIPPED_UNSUPPORTED_TYPE,

    /** Intervals.icu itself returns no data for the activity (the STRAVA stubs). */
    SKIPPED_NO_SOURCE_DATA,

    /** Attempted and failed; the row carries the reason and the attempt count. */
    FAILED,
    ;

    /** Whether this outcome means a RunningAI activity now carries the Intervals data. */
    val stored: Boolean get() = this == IMPORTED || this == REFRESHED || this == SKIPPED_ALREADY_IMPORTED
}

/** Everything the importer records about one activity. Field meanings are documented in V25. */
data class IntervalsImportRecord(
    val intervalsActivityId: String,
    val activityId: Long?,
    val outcome: IntervalsImportOutcome,
    val reasonCode: String?,
    val sourceLabel: String?,
    val sourceExternalId: String?,
    val stravaActivityId: String?,
    val deviceName: String?,
    val activityTypeLabel: String?,
    val startedAt: Instant?,
    val startDateLocal: String?,
    val elapsedTimeSeconds: Int?,
    val movingTimeSeconds: Int?,
    val distanceMeters: Double?,
    val sourceLapCount: Int?,
    val streamTypesAvailable: Int?,
    val streamTypesImported: Int?,
    val missingStreamTypes: String?,
    val sampleCount: Int?,
    val sampleSpanSeconds: Int?,
    val qualityFlags: String?,
    val sourceAnalyzedAt: Instant?,
    val attemptCount: Int,
    val lastErrorCode: String?,
    val importedAt: Instant?,
)

@Entity
@Table(name = "intervals_activity_import")
@EntityListeners(AuditingEntityListener::class)
class IntervalsActivityImportEntity(
    @Column(name = "athlete_id", nullable = false, updatable = false)
    var athleteId: Long,

    @Column(name = "intervals_activity_id", nullable = false, length = 100, updatable = false)
    var intervalsActivityId: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        private set

    @Column(name = "activity_id") var activityId: Long? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 32)
    var outcome: IntervalsImportOutcome = IntervalsImportOutcome.FAILED

    @Column(name = "reason_code", length = 64) var reasonCode: String? = null
    @Column(name = "source_label", length = 32) var sourceLabel: String? = null
    @Column(name = "source_external_id", length = 100) var sourceExternalId: String? = null
    @Column(name = "strava_activity_id", length = 100) var stravaActivityId: String? = null
    @Column(name = "device_name", length = 100) var deviceName: String? = null
    @Column(name = "activity_type_label", length = 32) var activityTypeLabel: String? = null
    @Column(name = "started_at") var startedAt: Instant? = null
    @Column(name = "start_date_local", length = 32) var startDateLocal: String? = null
    @Column(name = "elapsed_time_seconds") var elapsedTimeSeconds: Int? = null
    @Column(name = "moving_time_seconds") var movingTimeSeconds: Int? = null
    @Column(name = "distance_meters") var distanceMeters: Double? = null
    @Column(name = "source_lap_count") var sourceLapCount: Int? = null
    @Column(name = "stream_types_available") var streamTypesAvailable: Int? = null
    @Column(name = "stream_types_imported") var streamTypesImported: Int? = null
    @Column(name = "missing_stream_types", length = 400) var missingStreamTypes: String? = null
    @Column(name = "sample_count") var sampleCount: Int? = null
    @Column(name = "sample_span_seconds") var sampleSpanSeconds: Int? = null
    @Column(name = "quality_flags", length = 400) var qualityFlags: String? = null
    @Column(name = "source_analyzed_at") var sourceAnalyzedAt: Instant? = null
    @Column(name = "attempt_count", nullable = false) var attemptCount: Int = 0
    @Column(name = "last_error_code", length = 64) var lastErrorCode: String? = null
    @Column(name = "imported_at") var importedAt: Instant? = null

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null
        private set

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null
        private set

    fun toData() = IntervalsImportRecord(
        intervalsActivityId, activityId, outcome, reasonCode, sourceLabel, sourceExternalId,
        stravaActivityId, deviceName, activityTypeLabel, startedAt, startDateLocal, elapsedTimeSeconds,
        movingTimeSeconds, distanceMeters, sourceLapCount, streamTypesAvailable, streamTypesImported,
        missingStreamTypes, sampleCount, sampleSpanSeconds, qualityFlags, sourceAnalyzedAt,
        attemptCount, lastErrorCode, importedAt,
    )
}

interface IntervalsActivityImportRepository : JpaRepository<IntervalsActivityImportEntity, Long> {
    fun findByAthleteIdAndIntervalsActivityId(athleteId: Long, intervalsActivityId: String): IntervalsActivityImportEntity?

    fun findByAthleteIdOrderByStartedAtAsc(athleteId: Long): List<IntervalsActivityImportEntity>
}
