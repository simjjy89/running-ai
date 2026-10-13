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
 * What the Strava archive importer decided about one Strava activity (Phase 7E-2, V26).
 *
 * The row is the importer's idempotency key, resume point, progress log and data-quality record at
 * once, which is why it is written for every candidate - including the 46 whose sport no
 * [com.runningai.activity.ActivityType] models.
 */
enum class StravaImportOutcome {
    /** A new `activity` row was created from the archive's FIT file. */
    IMPORTED,

    /** An already-imported archive activity was re-imported because the file bytes changed. */
    REFRESHED,

    /** Unchanged since the last import; nothing was written. */
    SKIPPED_ALREADY_IMPORTED,

    /**
     * The workout already exists from another source. No second activity was created; the FIT's laps
     * and its NULL-only sample metrics were added to the activity that was already there.
     */
    ENRICHED_EXISTING,

    /** That enrichment was already applied from the same bytes. */
    SKIPPED_ALREADY_ENRICHED,

    /** Two or more stored activities could be this workout. Never merged automatically. */
    SKIPPED_AMBIGUOUS,

    /** No [com.runningai.activity.ActivityType] models this FIT sport/sub_sport. */
    SKIPPED_UNSUPPORTED_TYPE,

    /** The file decoded but carries no usable record data. */
    SKIPPED_NO_SENSOR_DATA,

    /** Attempted and failed; the row carries the reason and the attempt count. */
    FAILED,
    ;

    /** Whether this outcome means RunningAI now carries this file's data. */
    val stored: Boolean
        get() = this == IMPORTED || this == REFRESHED || this == SKIPPED_ALREADY_IMPORTED ||
            this == ENRICHED_EXISTING || this == SKIPPED_ALREADY_ENRICHED
}

/** How an archive file was tied to a stored activity. Identifiers only - never a pace or shape guess. */
enum class StravaMatchMethod {
    /** Strava's `apple_health_uuid` equals the Intervals.icu `external_id` of a stored activity. */
    APPLE_HEALTH_UUID,

    /** The Strava activity id equals the one a stored activity's Intervals import recorded. */
    STRAVA_ACTIVITY_ID,

    /** Nothing matched; the activity is this archive's own. */
    NONE,
}

/** Everything the importer records about one archive activity. Field meanings are documented in V26. */
data class StravaImportRecord(
    val stravaActivityId: String,
    val activityId: Long?,
    val outcome: StravaImportOutcome,
    val reasonCode: String?,
    val archiveFilename: String?,
    val fileSha256: String?,
    val appleHealthUuid: String?,
    val matchedBy: StravaMatchMethod?,
    val deviceManufacturer: String?,
    val fitSport: String?,
    val fitSubSport: String?,
    val activityType: String?,
    val startedAt: Instant?,
    val elapsedTimeSeconds: Int?,
    val timerTimeSeconds: Int?,
    val distanceMeters: Double?,
    val recordCount: Int?,
    val lapCount: Int?,
    val sampleCount: Int?,
    val lapsWritten: Int?,
    val samplesEnriched: Int?,
    val sensorChannels: String?,
    val qualityFlags: String?,
    val attemptCount: Int = 0,
    val lastErrorCode: String? = null,
    val importedAt: Instant? = null,
)

@Entity
@Table(name = "strava_activity_import")
@EntityListeners(AuditingEntityListener::class)
class StravaActivityImportEntity(

    @Column(name = "athlete_id", nullable = false)
    var athleteId: Long,

    @Column(name = "strava_activity_id", nullable = false, length = 100)
    var stravaActivityId: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "activity_id")
    var activityId: Long? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 32)
    var outcome: StravaImportOutcome = StravaImportOutcome.FAILED

    @Column(name = "reason_code", length = 64)
    var reasonCode: String? = null

    @Column(name = "archive_filename", length = 200)
    var archiveFilename: String? = null

    @Column(name = "file_sha256", length = 64)
    var fileSha256: String? = null

    @Column(name = "apple_health_uuid", length = 64)
    var appleHealthUuid: String? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "matched_by", length = 32)
    var matchedBy: StravaMatchMethod? = null

    @Column(name = "device_manufacturer", length = 64)
    var deviceManufacturer: String? = null

    @Column(name = "fit_sport", length = 40)
    var fitSport: String? = null

    @Column(name = "fit_sub_sport", length = 40)
    var fitSubSport: String? = null

    @Column(name = "activity_type", length = 32)
    var activityType: String? = null

    @Column(name = "started_at")
    var startedAt: Instant? = null

    @Column(name = "elapsed_time_seconds")
    var elapsedTimeSeconds: Int? = null

    @Column(name = "timer_time_seconds")
    var timerTimeSeconds: Int? = null

    @Column(name = "distance_meters")
    var distanceMeters: Double? = null

    @Column(name = "record_count")
    var recordCount: Int? = null

    @Column(name = "lap_count")
    var lapCount: Int? = null

    @Column(name = "sample_count")
    var sampleCount: Int? = null

    @Column(name = "laps_written")
    var lapsWritten: Int? = null

    @Column(name = "samples_enriched")
    var samplesEnriched: Int? = null

    @Column(name = "sensor_channels", length = 400)
    var sensorChannels: String? = null

    @Column(name = "quality_flags", length = 400)
    var qualityFlags: String? = null

    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int = 0

    @Column(name = "last_error_code", length = 64)
    var lastErrorCode: String? = null

    @Column(name = "imported_at")
    var importedAt: Instant? = null

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null

    fun toData(): StravaImportRecord = StravaImportRecord(
        stravaActivityId = stravaActivityId,
        activityId = activityId,
        outcome = outcome,
        reasonCode = reasonCode,
        archiveFilename = archiveFilename,
        fileSha256 = fileSha256,
        appleHealthUuid = appleHealthUuid,
        matchedBy = matchedBy,
        deviceManufacturer = deviceManufacturer,
        fitSport = fitSport,
        fitSubSport = fitSubSport,
        activityType = activityType,
        startedAt = startedAt,
        elapsedTimeSeconds = elapsedTimeSeconds,
        timerTimeSeconds = timerTimeSeconds,
        distanceMeters = distanceMeters,
        recordCount = recordCount,
        lapCount = lapCount,
        sampleCount = sampleCount,
        lapsWritten = lapsWritten,
        samplesEnriched = samplesEnriched,
        sensorChannels = sensorChannels,
        qualityFlags = qualityFlags,
        attemptCount = attemptCount,
        lastErrorCode = lastErrorCode,
        importedAt = importedAt,
    )
}

interface StravaActivityImportRepository : JpaRepository<StravaActivityImportEntity, Long> {
    fun findByAthleteIdAndStravaActivityId(athleteId: Long, stravaActivityId: String): StravaActivityImportEntity?
    fun findByAthleteIdOrderByStartedAtAsc(athleteId: Long): List<StravaActivityImportEntity>
}
