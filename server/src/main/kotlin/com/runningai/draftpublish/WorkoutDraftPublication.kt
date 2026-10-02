package com.runningai.draftpublish

import com.runningai.coach.ApprovedWorkoutDraft
import com.runningai.integration.intervals.IntervalsPublishOperation
import com.runningai.integration.intervals.IntervalsPublishResult
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.slf4j.LoggerFactory
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Successful outcome of publishing an approved draft. Failures are never stored. */
enum class DraftPublicationOutcome {
    /** Written to (or already matching on) Intervals.icu and verified. */
    PUBLISHED,

    /** An approved REST day: deliberately nothing was sent anywhere. */
    SKIPPED_REST_DAY,
}

data class WorkoutDraftPublication(
    val id: Long,
    val draftId: Long,
    val approvalId: Long,
    val outcome: DraftPublicationOutcome,
    val intervalsOperation: IntervalsPublishOperation?,
    val remoteEventId: String?,
    val verified: Boolean?,
    val publishedAt: Instant,
)

@Entity
@Table(name = "workout_draft_publication")
class WorkoutDraftPublicationEntity(

    @Column(name = "draft_id", nullable = false, updatable = false)
    var draftId: Long,

    @Column(name = "approval_id", nullable = false, updatable = false)
    var approvalId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 32, updatable = false)
    var outcome: DraftPublicationOutcome,

    @Enumerated(EnumType.STRING)
    @Column(name = "intervals_operation", length = 32, updatable = false)
    var intervalsOperation: IntervalsPublishOperation?,

    @Column(name = "remote_event_id", length = 128, updatable = false)
    var remoteEventId: String?,

    @Column(name = "verified", updatable = false)
    var verified: Boolean?,

    @Column(name = "published_at", nullable = false, updatable = false)
    var publishedAt: Instant,
) {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        private set
}

interface WorkoutDraftPublicationRepository : JpaRepository<WorkoutDraftPublicationEntity, Long> {

    fun findByDraftId(draftId: Long): WorkoutDraftPublicationEntity?
}

/**
 * Transaction boundary for publication records, separate from [ApprovedWorkoutDraftPublishService] so
 * that no database transaction is open while Intervals.icu is being called (and so `@Transactional`
 * is never self-invoked, which Spring would silently not apply).
 */
@Service
class WorkoutDraftPublicationStore(private val repository: WorkoutDraftPublicationRepository) {

    private val log = LoggerFactory.getLogger(WorkoutDraftPublicationStore::class.java)

    @Transactional(readOnly = true)
    fun find(draftId: Long): WorkoutDraftPublication? = repository.findByDraftId(draftId)?.let(::toDomain)

    @Transactional
    fun recordRestDay(approved: ApprovedWorkoutDraft): WorkoutDraftPublication =
        save(approved, DraftPublicationOutcome.SKIPPED_REST_DAY, null, null, null)

    @Transactional
    fun recordPublished(approved: ApprovedWorkoutDraft, result: IntervalsPublishResult): WorkoutDraftPublication =
        save(approved, DraftPublicationOutcome.PUBLISHED, result.operation(), result.remoteEventId(), result.verified())

    private fun save(
        approved: ApprovedWorkoutDraft,
        outcome: DraftPublicationOutcome,
        operation: IntervalsPublishOperation?,
        remoteEventId: String?,
        verified: Boolean?,
    ): WorkoutDraftPublication {
        val saved = repository.saveAndFlush(
            WorkoutDraftPublicationEntity(
                draftId = approved.approval.draftId,
                approvalId = approved.approval.id,
                outcome = outcome,
                intervalsOperation = operation,
                remoteEventId = remoteEventId,
                verified = verified,
                publishedAt = Instant.now().truncatedTo(ChronoUnit.MICROS),
            ),
        )
        log.info(
            "Workout draft publication recorded: draftId={} date={} outcome={} operation={}",
            saved.draftId, approved.draft.date, outcome, operation,
        )
        return toDomain(saved)
    }

    private fun toDomain(e: WorkoutDraftPublicationEntity) = WorkoutDraftPublication(
        id = requireNotNull(e.id),
        draftId = e.draftId,
        approvalId = e.approvalId,
        outcome = e.outcome,
        intervalsOperation = e.intervalsOperation,
        remoteEventId = e.remoteEventId,
        verified = e.verified,
        publishedAt = e.publishedAt,
    )
}
