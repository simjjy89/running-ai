package com.runningai.draftpublish

import com.runningai.injury.WorkoutSafetyViolationException
import com.runningai.integration.intervals.IntervalsException
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
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * How one publish attempt ended. Wider than [DraftPublicationOutcome], which only ever describes a
 * success, because the point of the attempt ledger is the outcomes that leave no publication row.
 */
enum class DraftPublishAttemptOutcome {
    /** Written to Intervals.icu and verified. */
    PUBLISHED,

    /** An approved REST day: nothing was sent anywhere, by design. */
    SKIPPED_REST_DAY,

    /** The stored result of an earlier publish was returned. Zero external calls. */
    ALREADY_PUBLISHED,

    /** RunningAI stopped it before any external call: the injury gate, or an unpublishable draft. */
    REFUSED,

    /** The external publish threw. */
    FAILED,
}

/**
 * One recorded attempt. [remoteStateCertain] is the field worth reading after a failure: false means
 * a write may or may not have landed on Intervals.icu, which is a different situation from a clean
 * refusal and calls for looking before acting.
 */
data class WorkoutDraftPublishAttempt(
    val id: Long,
    val draftId: Long,
    val approvalId: Long?,
    val attemptedAt: Instant,
    val completedAt: Instant,
    val durationMs: Long,
    val outcome: DraftPublishAttemptOutcome,
    val failureCode: String?,
    val httpStatus: Int?,
    val intervalsOperation: IntervalsPublishOperation?,
    val remoteEventId: String?,
    val externalWriteAttempted: Boolean,
    val remoteStateCertain: Boolean,
)

@Entity
@Table(name = "workout_draft_publish_attempt")
class WorkoutDraftPublishAttemptEntity(

    @Column(name = "draft_id", nullable = false, updatable = false)
    var draftId: Long,

    @Column(name = "approval_id", updatable = false)
    var approvalId: Long?,

    @Column(name = "attempted_at", nullable = false, updatable = false)
    var attemptedAt: Instant,

    @Column(name = "completed_at", nullable = false, updatable = false)
    var completedAt: Instant,

    @Column(name = "duration_ms", nullable = false, updatable = false)
    var durationMs: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 32, updatable = false)
    var outcome: DraftPublishAttemptOutcome,

    @Column(name = "failure_code", length = 64, updatable = false)
    var failureCode: String?,

    @Column(name = "http_status", updatable = false)
    var httpStatus: Int?,

    @Enumerated(EnumType.STRING)
    @Column(name = "intervals_operation", length = 32, updatable = false)
    var intervalsOperation: IntervalsPublishOperation?,

    @Column(name = "remote_event_id", length = 128, updatable = false)
    var remoteEventId: String?,

    @Column(name = "external_write_attempted", nullable = false, updatable = false)
    var externalWriteAttempted: Boolean,

    @Column(name = "remote_state_certain", nullable = false, updatable = false)
    var remoteStateCertain: Boolean,
) {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        private set
}

interface WorkoutDraftPublishAttemptRepository : JpaRepository<WorkoutDraftPublishAttemptEntity, Long> {

    fun findByDraftIdOrderByAttemptedAtDescIdDesc(draftId: Long): List<WorkoutDraftPublishAttemptEntity>
}

/**
 * Writes the attempt ledger (Phase 8B).
 *
 * Every write is [Propagation.REQUIRES_NEW] for the same reason the Garmin attempt ledger is: the
 * record of a failure has to survive whatever the failing caller does with its own transaction.
 * And recording is never allowed to change an outcome - [DraftPublishAttemptRecorder] swallows any
 * failure here, so a full disk cannot turn a successful publish into an error.
 *
 * Nothing in the publish path READS this table. It is a record, not a gate: making a retry depend
 * on it would quietly add a second source of truth next to the publication row and the Intervals
 * marker, which are the two that actually prevent a duplicate workout.
 */
@Service
class WorkoutDraftPublishAttemptStore(private val repository: WorkoutDraftPublishAttemptRepository) {

    @Transactional(readOnly = true)
    fun history(draftId: Long): List<WorkoutDraftPublishAttempt> =
        repository.findByDraftIdOrderByAttemptedAtDescIdDesc(draftId).map(::toDomain)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun record(
        draftId: Long,
        approvalId: Long?,
        attemptedAt: Instant,
        completedAt: Instant,
        outcome: DraftPublishAttemptOutcome,
        failureCode: String?,
        httpStatus: Int?,
        result: IntervalsPublishResult?,
        externalWriteAttempted: Boolean,
        remoteStateCertain: Boolean,
    ): WorkoutDraftPublishAttempt = toDomain(
        repository.saveAndFlush(
            WorkoutDraftPublishAttemptEntity(
                draftId = draftId,
                approvalId = approvalId,
                attemptedAt = attemptedAt.truncatedTo(ChronoUnit.MICROS),
                completedAt = completedAt.truncatedTo(ChronoUnit.MICROS),
                durationMs = maxOf(0, completedAt.toEpochMilli() - attemptedAt.toEpochMilli()),
                outcome = outcome,
                failureCode = failureCode,
                httpStatus = httpStatus,
                intervalsOperation = result?.operation(),
                remoteEventId = result?.remoteEventId(),
                externalWriteAttempted = externalWriteAttempted,
                remoteStateCertain = remoteStateCertain,
            ),
        ),
    )

    private fun toDomain(e: WorkoutDraftPublishAttemptEntity) = WorkoutDraftPublishAttempt(
        id = requireNotNull(e.id),
        draftId = e.draftId,
        approvalId = e.approvalId,
        attemptedAt = e.attemptedAt,
        completedAt = e.completedAt,
        durationMs = e.durationMs,
        outcome = e.outcome,
        failureCode = e.failureCode,
        httpStatus = e.httpStatus,
        intervalsOperation = e.intervalsOperation,
        remoteEventId = e.remoteEventId,
        externalWriteAttempted = e.externalWriteAttempted,
        remoteStateCertain = e.remoteStateCertain,
    )
}

/**
 * Turns the end of a publish attempt into one ledger row, and guarantees that doing so cannot
 * change what the publish returned or threw.
 *
 * The failure code is built from the exception's TYPE and reason only, never its message, so no
 * response body, workout text or credential can reach the database through this path - the same
 * rule `GarminCollectionFailureCode` follows for the collection side.
 */
@Service
class DraftPublishAttemptRecorder(private val store: WorkoutDraftPublishAttemptStore) {

    private val log = LoggerFactory.getLogger(DraftPublishAttemptRecorder::class.java)

    fun recordSuccess(
        draftId: Long,
        approvalId: Long?,
        attemptedAt: Instant,
        completedAt: Instant,
        outcome: DraftPublishAttemptOutcome,
        result: IntervalsPublishResult?,
        externalWriteAttempted: Boolean,
    ) = swallow {
        store.record(
            draftId = draftId,
            approvalId = approvalId,
            attemptedAt = attemptedAt,
            completedAt = completedAt,
            outcome = outcome,
            failureCode = null,
            httpStatus = null,
            result = result,
            externalWriteAttempted = externalWriteAttempted,
            remoteStateCertain = true,
        )
    }

    fun recordFailure(
        draftId: Long,
        approvalId: Long?,
        attemptedAt: Instant,
        completedAt: Instant,
        failure: Throwable,
        externalWriteAttempted: Boolean,
    ) = swallow {
        val intervals = failure as? IntervalsException
        store.record(
            draftId = draftId,
            approvalId = approvalId,
            attemptedAt = attemptedAt,
            completedAt = completedAt,
            outcome = if (externalWriteAttempted) {
                DraftPublishAttemptOutcome.FAILED
            } else {
                DraftPublishAttemptOutcome.REFUSED
            },
            failureCode = failureCode(failure),
            httpStatus = intervals?.httpStatus,
            result = null,
            externalWriteAttempted = externalWriteAttempted,
            // Only an external attempt can leave the remote side in an unknown state; a local
            // refusal never touched it, so "certain" is simply true there.
            remoteStateCertain = !externalWriteAttempted || intervals?.isOutcomeUnknown != true,
        )
    }

    private fun swallow(action: () -> Unit) {
        try {
            action()
        } catch (e: RuntimeException) {
            // A publish that worked must not be reported as broken because its audit row would not
            // save, and a publish that failed must keep the failure it actually had.
            log.warn("Workout draft publish attempt could not be recorded: {}", e.javaClass.simpleName)
        }
    }

    companion object {
        private const val MAX_CODE_LENGTH = 64

        /** Type and reason only - never `failure.message`, which is not guaranteed to be sanitised. */
        fun failureCode(failure: Throwable): String {
            val code = when (failure) {
                is IntervalsException -> failure.code
                is UnpublishableDraftException -> "UNPUBLISHABLE_DRAFT"
                // The same string the HTTP layer returns for this refusal, so the ledger and the
                // response a caller saw cannot disagree about why a publish was stopped.
                is WorkoutSafetyViolationException -> "WORKOUT_SAFETY_" + failure.decision.reasonCode.name
                else -> "UNEXPECTED_" + failure.javaClass.simpleName
            }
            return if (code.length <= MAX_CODE_LENGTH) code else code.take(MAX_CODE_LENGTH)
        }
    }
}
