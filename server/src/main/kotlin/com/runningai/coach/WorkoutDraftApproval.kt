package com.runningai.coach

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Another draft is already approved for the same athlete and date; at most one approved workout per day. */
class WorkoutDateAlreadyApprovedException(id: Long, date: LocalDate, val approvedDraftId: Long?) :
    RuntimeException(
        "Workout draft $id cannot be approved: " +
            (approvedDraftId?.let { "draft $it" } ?: "another draft") + " is already approved for $date",
    )

/**
 * The athlete's explicit approval of one draft. Lifecycle fact only: an approval never publishes,
 * and the publish outcome is recorded elsewhere.
 */
data class WorkoutDraftApproval(
    val id: Long,
    val draftId: Long,
    val athleteId: Long,
    val date: LocalDate,
    val approvedAt: Instant,
)

/** An approved draft together with its approval record. */
data class ApprovedWorkoutDraft(val draft: WorkoutDraft, val approval: WorkoutDraftApproval)

@Entity
@Table(name = "workout_draft_approval")
class WorkoutDraftApprovalEntity(

    @Column(name = "draft_id", nullable = false, updatable = false)
    var draftId: Long,

    @Column(name = "athlete_id", nullable = false, updatable = false)
    var athleteId: Long,

    @Column(name = "workout_date", nullable = false, updatable = false)
    var workoutDate: LocalDate,

    @Column(name = "approved_at", nullable = false, updatable = false)
    var approvedAt: Instant,
) {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        private set

    fun toDomain() = WorkoutDraftApproval(requireNotNull(id), draftId, athleteId, workoutDate, approvedAt)
}

interface WorkoutDraftApprovalRepository : JpaRepository<WorkoutDraftApprovalEntity, Long> {

    fun findByDraftId(draftId: Long): WorkoutDraftApprovalEntity?

    fun findByAthleteIdAndWorkoutDate(athleteId: Long, workoutDate: LocalDate): WorkoutDraftApprovalEntity?
}

/**
 * Approves workout drafts (Phase 6G). **A database lifecycle operation and nothing else**: it has no
 * dependency on any publishing, Intervals or Garmin type (enforced by `CoachArchitectureTest`), so
 * approving can never cause an external write, whatever any publish switch is set to.
 *
 * Rules:
 *  - only the current version (status DRAFT) can be approved; a SUPERSEDED version cannot;
 *  - at most one approved draft per athlete and date (checked here, and enforced by the database);
 *  - approving the same draft again is idempotent: the existing approval is returned unchanged;
 *  - the status change and the approval row are written in one transaction.
 */
@Service
class WorkoutDraftApprovalService(
    private val drafts: WorkoutDraftRepository,
    private val approvals: WorkoutDraftApprovalRepository,
    private val store: WorkoutDraftStore,
) {

    private val log = LoggerFactory.getLogger(WorkoutDraftApprovalService::class.java)

    @Transactional
    fun approve(id: Long): ApprovedWorkoutDraft {
        val draft = drafts.findById(id).orElseThrow { WorkoutDraftNotFoundException(id) }
        when (draft.status) {
            WorkoutDraftStatus.APPROVED -> return existing(draft)
            WorkoutDraftStatus.SUPERSEDED -> throw superseded(draft)
            WorkoutDraftStatus.DRAFT -> Unit
        }

        approvals.findByAthleteIdAndWorkoutDate(draft.athleteId, draft.workoutDate)?.let {
            throw WorkoutDateAlreadyApprovedException(id, draft.workoutDate, it.draftId)
        }

        // Compare-and-set: a concurrent revision or approval of this same draft may have changed it
        // since it was read above. Exactly one transition out of DRAFT wins.
        if (drafts.transitionStatus(id, WorkoutDraftStatus.DRAFT, WorkoutDraftStatus.APPROVED, Instant.now()) == 0) {
            val now = drafts.findById(id).orElseThrow { WorkoutDraftNotFoundException(id) }
            if (now.status == WorkoutDraftStatus.APPROVED) {
                return existing(now)
            }
            throw superseded(now)
        }

        val approval = try {
            approvals.saveAndFlush(
                WorkoutDraftApprovalEntity(
                    draftId = id,
                    athleteId = draft.athleteId,
                    workoutDate = draft.workoutDate,
                    approvedAt = Instant.now().truncatedTo(ChronoUnit.MICROS),
                ),
            )
        } catch (e: DataIntegrityViolationException) {
            // Lost a race with the approval of another draft for the same date. This transaction
            // (including the status change above) is rolled back; no further query is issued here
            // because PostgreSQL refuses statements in an aborted transaction.
            throw WorkoutDateAlreadyApprovedException(id, draft.workoutDate, approvedDraftId = null)
        }
        log.info(
            "Workout draft approved: id={} group={} version={} date={} type={}",
            id, draft.draftGroupId, draft.version, draft.workoutDate, draft.workoutType,
        )
        val reloaded = drafts.findById(id).orElseThrow { WorkoutDraftNotFoundException(id) }
        return ApprovedWorkoutDraft(store.toDomain(reloaded), approval.toDomain())
    }

    /** The approval of [id], or null when the draft is not approved. Read-only. */
    @Transactional(readOnly = true)
    fun findApproved(id: Long): ApprovedWorkoutDraft? {
        val draft = drafts.findById(id).orElseThrow { WorkoutDraftNotFoundException(id) }
        if (draft.status != WorkoutDraftStatus.APPROVED) {
            return null
        }
        val approval = approvals.findByDraftId(id) ?: return null
        return ApprovedWorkoutDraft(store.toDomain(draft), approval.toDomain())
    }

    private fun existing(draft: WorkoutDraftEntity): ApprovedWorkoutDraft {
        val id = requireNotNull(draft.id)
        val approval = approvals.findByDraftId(id)
            ?: throw IllegalStateException("Workout draft $id is APPROVED but has no approval record")
        return ApprovedWorkoutDraft(store.toDomain(draft), approval.toDomain())
    }

    private fun superseded(draft: WorkoutDraftEntity): WorkoutDraftSupersededException {
        val latest = drafts.findByDraftGroupIdOrderByVersionDesc(draft.draftGroupId).first()
        return WorkoutDraftSupersededException(requireNotNull(draft.id), latest.version)
    }
}
