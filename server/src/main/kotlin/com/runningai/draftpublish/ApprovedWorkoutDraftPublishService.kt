package com.runningai.draftpublish

import com.runningai.coach.ApprovedWorkoutDraft
import com.runningai.coach.WorkoutDraftApprovalService
import com.runningai.coach.WorkoutDraftStatus
import com.runningai.coach.WorkoutDraftStore
import com.runningai.injury.WorkoutSafetyGateService
import com.runningai.integration.intervals.IntervalsWorkoutPublisher
import com.runningai.integration.intervals.IntervalsWorkoutRenderer
import com.runningai.integration.intervals.RenderedIntervalsWorkout
import com.runningai.training.StructuredWorkout
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Publishing approved drafts is switched off (`RUNNING_AI_DRAFT_PUBLISHING_ENABLED=false`). */
class DraftPublishingDisabledException :
    RuntimeException("Publishing approved workout drafts is disabled (RUNNING_AI_DRAFT_PUBLISHING_ENABLED=false)")

/** A publish of this same draft is already running in this JVM. */
class DraftPublishAlreadyRunningException(id: Long) :
    RuntimeException("A publish of workout draft $id is already running")

/** The draft is not APPROVED (still a DRAFT, or SUPERSEDED): only an approved draft may be previewed or published. */
class WorkoutDraftNotApprovedException(id: Long, val status: WorkoutDraftStatus) :
    RuntimeException("Workout draft $id is $status; only an APPROVED draft can be previewed or published")

/** The approved draft cannot be expressed by the verified renderer without changing it. Nothing was sent. */
class UnpublishableDraftException(id: Long, val reasons: List<String>) :
    RuntimeException("Workout draft $id cannot be published without changing it: ${reasons.joinToString("; ")}")

/** What would be sent for an approved draft, computed without any external call. */
data class DraftPublishPreview(
    val approved: ApprovedWorkoutDraft,
    val workout: StructuredWorkout?,
    val rendered: RenderedIntervalsWorkout?,
    val unpublishableReasons: List<String>,
    val publication: WorkoutDraftPublication?,
) {
    val restDay: Boolean get() = approved.draft.isRest
    val publishable: Boolean get() = unpublishableReasons.isEmpty()
}

/** The result of a publish request; [alreadyPublished] = the stored result of an earlier publish was returned. */
data class DraftPublishOutcome(
    val approved: ApprovedWorkoutDraft,
    val publication: WorkoutDraftPublication,
    val structuredStepCount: Int?,
    val alreadyPublished: Boolean,
)

/**
 * The only path from an APPROVED AI-coach draft to Intervals.icu (Phase 6G):
 *
 * approved draft → (REST? → `SKIPPED_REST_DAY`, nothing sent) → publishability check →
 * [WorkoutDraftStructuredWorkoutMapper] → existing [IntervalsWorkoutRenderer] → existing
 * [IntervalsWorkoutPublisher] → publication record.
 *
 * It publishes **exactly the approved draft**. It deliberately does not use
 * `WorkoutPublishApplicationService` or `WorkoutIntensityTargetService`: those compute a fresh
 * deterministic prescription for the date, which would publish a workout the athlete never approved.
 *
 * Safety:
 *  - off unless `running-ai.draft-publishing.enabled` (separate from the legacy switch);
 *  - only APPROVED drafts; a DRAFT or SUPERSEDED one is refused before anything else happens;
 *  - a REST day returns before the renderer, publisher or client is touched — no empty workout, no
 *    calendar placeholder;
 *  - unpublishable drafts fail closed before the publisher is called;
 *  - idempotent: a stored successful publication is returned without calling the publisher again; a
 *    failed publish stores no PUBLICATION (the draft stays APPROVED and can be published again, and
 *    the publisher's own marker lookup prevents a duplicate remote event) - since Phase 8B it does
 *    store an ATTEMPT, which records what went wrong without claiming a workout exists remotely;
 *  - single-flight per draft id in this JVM (single-instance deployment), with the unique publication
 *    row as the last line of defence; different drafts never block each other;
 *  - not `@Transactional`: reads and writes go through their own transactional stores, so no database
 *    transaction stays open across the Intervals HTTP calls.
 *
 * Only reachable from an explicit HTTP request. There is no scheduler, no startup trigger and no MCP
 * tool for it, and the AI coach has no dependency on it.
 */
@Service
class ApprovedWorkoutDraftPublishService(
    private val properties: DraftPublishingProperties,
    private val approvals: WorkoutDraftApprovalService,
    private val drafts: WorkoutDraftStore,
    private val publications: WorkoutDraftPublicationStore,
    private val validator: ApprovedWorkoutDraftPublishabilityValidator,
    private val mapper: WorkoutDraftStructuredWorkoutMapper,
    private val renderer: IntervalsWorkoutRenderer,
    private val publisher: IntervalsWorkoutPublisher,
    private val safetyGate: WorkoutSafetyGateService,
    private val attemptRecorder: DraftPublishAttemptRecorder,
    private val attemptLedger: WorkoutDraftPublishAttemptStore,
) {

    private val log = LoggerFactory.getLogger(ApprovedWorkoutDraftPublishService::class.java)
    private val inFlight: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /** Read-only: what a publish would send. Works with the publish switch off; never calls Intervals. */
    fun preview(id: Long): DraftPublishPreview {
        val approved = loadApproved(id)
        val publication = publications.find(id)
        if (approved.draft.isRest) {
            return DraftPublishPreview(approved, null, null, emptyList(), publication)
        }
        // Phase 7B-2: the safety state may have worsened since approval; a preview of a workout that
        // may no longer be published must say so rather than quietly showing what would be sent.
        safetyGate.requireAllowed(safetyGate.currentDecision(), isRest = false, context = "publish preview of workout draft $id")
        return when (val prepared = prepare(approved)) {
            is Prepared.Ready -> DraftPublishPreview(approved, prepared.workout, prepared.rendered, emptyList(), publication)
            is Prepared.Refused -> DraftPublishPreview(approved, null, null, prepared.reasons, publication)
        }
    }

    /** The attempt history of one draft, newest first; read-only and never contacts Intervals. */
    fun attempts(id: Long): List<WorkoutDraftPublishAttempt> {
        drafts.get(id)        // 404 for an unknown draft, rather than an empty list that reads as "never tried"
        return attemptLedger.history(id)
    }

    fun publish(id: Long): DraftPublishOutcome {
        if (!properties.enabled) {
            throw DraftPublishingDisabledException()
        }
        if (!inFlight.add(id)) {
            log.info("Workout draft publish rejected: draftId={} already running", id)
            throw DraftPublishAlreadyRunningException(id)
        }
        val attemptedAt = Instant.now()
        var externalWriteAttempted = false
        val approved = try {
            loadApproved(id)
        } catch (e: RuntimeException) {
            inFlight.remove(id)
            throw e
        }
        // Phase 8B: from here on the attempt is recorded however it ends. The rejections above are
        // deliberately not recorded - a disabled switch, a concurrent request and an unapproved
        // draft never reached an approved draft, so none of them is an attempt to publish one. The
        // Garmin collection ledger draws the same line for the same reason.
        val approval: Long = approved.approval.id
        try {
            publications.find(id)?.let {
                log.info("Workout draft publish: draftId={} already {}; returning the stored result", id, it.outcome)
                attemptRecorder.recordSuccess(id, approval, attemptedAt, Instant.now(),
                    DraftPublishAttemptOutcome.ALREADY_PUBLISHED, null, externalWriteAttempted = false)
                return DraftPublishOutcome(approved, it, structuredStepCount = null, alreadyPublished = true)
            }

            if (approved.draft.isRest) {
                val skipped = publications.recordRestDay(approved)
                log.info("Workout draft publish: draftId={} date={} REST -> SKIPPED_REST_DAY (no external call)",
                    id, approved.draft.date)
                attemptRecorder.recordSuccess(id, approval, attemptedAt, Instant.now(),
                    DraftPublishAttemptOutcome.SKIPPED_REST_DAY, null, externalWriteAttempted = false)
                return DraftPublishOutcome(approved, skipped, structuredStepCount = 0, alreadyPublished = false)
            }

            // Phase 7B-2: re-checked immediately before anything is sent to Intervals, independently
            // of the safety state at generation or approval time.
            safetyGate.requireAllowed(safetyGate.currentDecision(), isRest = false, context = "publish of workout draft $id")

            val ready = when (val prepared = prepare(approved)) {
                is Prepared.Ready -> prepared
                is Prepared.Refused -> {
                    log.info("Workout draft publish refused: draftId={} unpublishable ({} reasons)", id, prepared.reasons.size)
                    throw UnpublishableDraftException(id, prepared.reasons)
                }
            }
            // Any failure here propagates and no PUBLICATION is recorded: the draft stays APPROVED and
            // can be published again. The attempt itself is recorded, which is a different thing - it
            // says what happened without claiming a workout exists on Intervals.icu.
            externalWriteAttempted = true
            val result = publisher.publish(approved.draft.date, ready.rendered)
            val published = publications.recordPublished(approved, result)
            log.info("Workout draft publish: draftId={} date={} operation={} verified={}",
                id, approved.draft.date, result.operation(), result.verified())
            attemptRecorder.recordSuccess(id, approval, attemptedAt, Instant.now(),
                DraftPublishAttemptOutcome.PUBLISHED, result, externalWriteAttempted = true)
            return DraftPublishOutcome(approved, published, ready.workout.steps().size, alreadyPublished = false)
        } catch (e: RuntimeException) {
            attemptRecorder.recordFailure(id, approval, attemptedAt, Instant.now(), e, externalWriteAttempted)
            throw e
        } finally {
            inFlight.remove(id)
        }
    }

    private fun loadApproved(id: Long): ApprovedWorkoutDraft =
        approvals.findApproved(id) ?: throw WorkoutDraftNotApprovedException(id, drafts.get(id).status)

    private fun prepare(approved: ApprovedWorkoutDraft): Prepared {
        val draft = approved.draft
        validator.problems(draft).takeIf { it.isNotEmpty() }?.let { return Prepared.Refused(it) }
        val workout = mapper.map(draft)
        validator.mappedProblems(draft, workout).takeIf { it.isNotEmpty() }?.let { return Prepared.Refused(it) }
        val rendered = renderer.render(workout)
        if (rendered.workoutText().isNullOrBlank()) {
            return Prepared.Refused(listOf("the rendered workout is empty"))
        }
        return Prepared.Ready(workout, rendered)
    }

    private sealed interface Prepared {
        data class Ready(val workout: StructuredWorkout, val rendered: RenderedIntervalsWorkout) : Prepared
        data class Refused(val reasons: List<String>) : Prepared
    }
}
