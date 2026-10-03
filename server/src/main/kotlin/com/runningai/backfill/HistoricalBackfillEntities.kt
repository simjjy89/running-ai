package com.runningai.backfill

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
import java.time.LocalDate

enum class BackfillRunStatus { RUNNING, PAUSED, COMPLETED, FAILED }

/** The phases in execution order; a resumed run re-enters at its stored phase. */
enum class BackfillPhase {
    GARMIN_DISCOVERY,
    GARMIN_DETAIL_ANALYSIS,
    INTERVALS_ACTIVITIES,
    INTERVALS_FITNESS,
    GARMIN_RECOVERY,
    VERIFY,
    COMPLETED,
}

enum class BackfillSummaryStatus { CREATED, UPDATED, SKIPPED_UNSUPPORTED }

/** How this run satisfied the detail stage for one activity. */
enum class BackfillDetailStatus {
    /** Stored detail already passed every gate; Garmin was not called. */
    SKIPPED_ALREADY_COMPLETE,

    /** This run fetched the detail parts and they passed every gate. */
    COLLECTED,
}

/** Stream fidelity as the backfill judges it; only passing values are ever stored on an item. */
enum class BackfillSampleOutcome {
    /** Normalised stream, `sample_completeness = FULL`. */
    FULL,

    /** The source legitimately has no sample stream (part EMPTY); lap/zone analysis still runs. */
    NO_SAMPLE_STREAM,
}

enum class BackfillIntervalsStatus { MATCHED, UNMATCHED, AMBIGUOUS }

@Entity
@Table(name = "historical_backfill_run")
@EntityListeners(AuditingEntityListener::class)
class HistoricalBackfillRunEntity(
    @Column(name = "athlete_id", nullable = false, updatable = false)
    var athleteId: Long,

    @Column(name = "start_date", nullable = false, updatable = false)
    var startDate: LocalDate,

    @Column(name = "end_date", nullable = false, updatable = false)
    var endDate: LocalDate,

    @Column(name = "requested_days", nullable = false, updatable = false)
    var requestedDays: Int,

    @Column(name = "started_at", nullable = false, updatable = false)
    var startedAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        private set

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: BackfillRunStatus = BackfillRunStatus.RUNNING

    @Enumerated(EnumType.STRING)
    @Column(name = "current_phase", nullable = false, length = 32)
    var currentPhase: BackfillPhase = BackfillPhase.GARMIN_DISCOVERY

    @Column(name = "stop_reason", length = 64)
    var stopReason: String? = null

    /** Set once discovery finished a full pass; the target set is frozen from then on (§14). */
    @Column(name = "discovery_complete", nullable = false)
    var discoveryComplete: Boolean = false

    @Column(name = "target_activity_count")
    var targetActivityCount: Int? = null

    @Column(name = "completed_activity_count", nullable = false)
    var completedActivityCount: Int = 0

    @Column(name = "fitness_day_count")
    var fitnessDayCount: Int? = null

    @Column(name = "recovery_day_count", nullable = false)
    var recoveryDayCount: Int = 0

    /** Newest-first recovery cursor: the next day still to sync; null until the recovery phase starts. */
    @Column(name = "next_recovery_date")
    var nextRecoveryDate: LocalDate? = null

    @Column(name = "completed_at")
    var completedAt: Instant? = null

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null
        private set

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null
        private set
}

interface HistoricalBackfillRunRepository : JpaRepository<HistoricalBackfillRunEntity, Long> {
    fun existsByAthleteIdAndStatus(athleteId: Long, status: BackfillRunStatus): Boolean
}

@Entity
@Table(name = "historical_backfill_activity")
@EntityListeners(AuditingEntityListener::class)
class HistoricalBackfillActivityEntity(
    @Column(name = "run_id", nullable = false, updatable = false)
    var runId: Long,

    @Column(name = "garmin_external_id", nullable = false, length = 100, updatable = false)
    var garminExternalId: String,

    @Column(name = "started_at", nullable = false)
    var startedAt: Instant,

    @Column(name = "activity_id") var activityId: Long?,

    @Enumerated(EnumType.STRING)
    @Column(name = "summary_status", nullable = false, length = 32)
    var summaryStatus: BackfillSummaryStatus,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        private set

    /** Frozen processing position (started_at ASC), assigned when discovery completes. */
    @Column(name = "ordinal") var ordinal: Int? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "detail_status", length = 32)
    var detailStatus: BackfillDetailStatus? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "sample_completeness", length = 24)
    var sampleOutcome: BackfillSampleOutcome? = null

    /** The stored analysis' own status (COMPLETE / PARTIAL / INSUFFICIENT_DATA) once this stage passed. */
    @Column(name = "analysis_status", length = 32)
    var analysisStatus: String? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "intervals_status", length = 32)
    var intervalsStatus: BackfillIntervalsStatus? = null

    @Column(name = "last_error_code", length = 64)
    var lastErrorCode: String? = null

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null
        private set

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null
        private set

    /** Detail + analysis both done: the activity never needs Garmin again in this run. */
    fun garminStagesComplete(): Boolean = detailStatus != null && analysisStatus != null
}

interface HistoricalBackfillActivityRepository : JpaRepository<HistoricalBackfillActivityEntity, Long> {
    fun findByRunIdAndGarminExternalId(runId: Long, garminExternalId: String): HistoricalBackfillActivityEntity?

    fun findByRunIdOrderByStartedAtAscGarminExternalIdAsc(runId: Long): List<HistoricalBackfillActivityEntity>

    fun countByRunIdAndSummaryStatusNot(runId: Long, summaryStatus: BackfillSummaryStatus): Int
}
