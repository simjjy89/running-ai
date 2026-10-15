package com.runningai.delivery

import com.fasterxml.jackson.annotation.JsonInclude
import com.runningai.coach.WorkoutDraftApprovalService
import com.runningai.coach.WorkoutDraftNotFoundException
import com.runningai.common.exception.ErrorResponse
import com.runningai.draftpublish.DraftPublicationOutcome
import com.runningai.draftpublish.WorkoutDraftNotApprovedException
import com.runningai.draftpublish.WorkoutDraftPublication
import com.runningai.draftpublish.WorkoutDraftPublicationStore
import com.runningai.integration.garmin.GarminConnectorException
import com.runningai.integration.garmin.GarminScheduledWorkoutClient
import com.runningai.integration.garmin.GarminScheduledWorkoutReader
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import com.runningai.coach.WorkoutDraftStatus
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.YearMonth

/** What RunningAI itself did with the approved draft. Database-only; always knowable. */
enum class IntervalsDeliveryStatus {
    /** Nothing was published: no publication row exists for this draft. */
    NOT_PUBLISHED,

    /** An approved REST day. Nothing was sent anywhere, by design, and nothing ever will be. */
    REST_DAY_NOT_SENT,

    /** Written to Intervals.icu and verified by readback. */
    PUBLISHED_AND_VERIFIED,

    /** A publication exists but was not readback-verified. Should not occur; reported if it does. */
    PUBLISHED_UNVERIFIED,
}

/**
 * What Garmin's own workout calendar shows. Every value here is an observation, never a verdict -
 * see the note on [GARMIN_NOT_FOUND] in particular.
 */
enum class GarminDeliveryStatus {
    /** Garmin's calendar holds a scheduled workout on the date. */
    GARMIN_PRESENT,

    /**
     * Garmin's calendar holds no scheduled workout on the date. **This is not proof of failure.**
     * python-garminconnect documents that the calendar service returns only workouts already
     * committed to the visible calendar, and the Intervals.icu push may simply not have run yet.
     * Read it together with [WorkoutDeliveryStatus.minutesSincePublish].
     */
    GARMIN_NOT_FOUND,

    /** Garmin could not be asked (connector down, session expired, rate limited). Nothing is known. */
    GARMIN_LOOKUP_FAILED,

    /** Not asked: either the caller declined the lookup, or nothing was published to look for. */
    GARMIN_NOT_CHECKED,
}

/**
 * `GET /api/v1/workout-drafts/{id}/delivery-status`. Facts only, in three layers, and the third is
 * deliberately always the same answer.
 *
 * Whether the workout reached the WATCH cannot be determined by any API available here: Garmin
 * Connect knows what it holds, not what the device has synced. Rather than letting
 * [GarminDeliveryStatus.GARMIN_PRESENT] be mistaken for "it is on the watch", that question is
 * answered explicitly and permanently with [deviceDelivery] = `UNKNOWN`.
 *
 * This lives in its own package because it is the one place that reads the draft lifecycle AND
 * Garmin in the same breath. `DraftPublishArchitectureTest` forbids any `draftpublish` source from
 * importing `integration.garmin` at all - a blunt guard, deliberately, so the path that sends
 * workouts can never acquire a Garmin route - and a read-only verifier is not a reason to loosen
 * it. The dependencies run one way: delivery -> draftpublish, delivery -> garmin, neither back.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class WorkoutDeliveryStatus(
    val draftId: Long,
    val date: java.time.LocalDate,
    val workoutType: String,
    val intervalsDelivery: IntervalsDeliveryStatus,
    val intervalsEventId: String?,
    val publishedAt: Instant?,
    /** How long ago the publish happened, so a recent absence on Garmin reads as "not yet". */
    val minutesSincePublish: Long?,
    val garminDelivery: GarminDeliveryStatus,
    /** Error code when the Garmin lookup failed; null otherwise. Codes only, never a message. */
    val garminLookupFailureCode: String?,
    val garminScheduledWorkoutCount: Int,
    val garminCheckedAt: Instant?,
    /** Always `UNKNOWN`: no available API reports what the watch has actually synced. */
    val deviceDelivery: String = "UNKNOWN",
)

/**
 * Answers "did the workout actually get anywhere?" (Phase 8B, Workstream E).
 *
 * Until now the only evidence that a published workout reached the watch was one person looking at
 * a Garmin FR265 once, in Phase 5C-4. The Intervals.icu side has been verifiable since 5C-3 - the
 * publisher reads its own write back - but the hop from Intervals.icu to Garmin Connect is
 * **Intervals.icu's own integration**, which this repository neither performs nor controls.
 *
 * So this reads, and never writes. It asks Garmin's calendar what it holds and reports that beside
 * what RunningAI did. It cannot push a workout to Garmin, and no Garmin write path exists anywhere
 * in this repository or the connector - a connector test fails the build if one appears.
 *
 * It states facts and draws no conclusion. "Garmin has no workout on that date" is reported as
 * exactly that, never as a failure: the calendar service only returns committed workouts, and a
 * push that has not run yet looks identical to one that never will. [minutesSincePublish] is given
 * so the reader can tell a two-minute-old publish from a two-day-old one, which is a judgement for
 * a human or the coach, not for this service.
 */
@Service
class WorkoutDeliveryStatusService(
    private val approvals: WorkoutDraftApprovalService,
    private val publications: WorkoutDraftPublicationStore,
    private val scheduledWorkouts: GarminScheduledWorkoutClient,
    private val reader: GarminScheduledWorkoutReader,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(WorkoutDeliveryStatusService::class.java)

    /**
     * @param checkGarmin false skips the Garmin call entirely, making this database-only
     */
    fun of(draftId: Long, checkGarmin: Boolean): WorkoutDeliveryStatus {
        val approved = approvals.findApproved(draftId)
            ?: throw WorkoutDraftNotApprovedException(draftId, approvals.statusOf(draftId))
        val publication = publications.find(draftId)

        val intervalsDelivery = when {
            publication == null -> IntervalsDeliveryStatus.NOT_PUBLISHED
            publication.outcome == DraftPublicationOutcome.SKIPPED_REST_DAY -> IntervalsDeliveryStatus.REST_DAY_NOT_SENT
            publication.verified == true -> IntervalsDeliveryStatus.PUBLISHED_AND_VERIFIED
            else -> IntervalsDeliveryStatus.PUBLISHED_UNVERIFIED
        }
        val sentToIntervals = intervalsDelivery == IntervalsDeliveryStatus.PUBLISHED_AND_VERIFIED ||
            intervalsDelivery == IntervalsDeliveryStatus.PUBLISHED_UNVERIFIED

        // Nothing was sent, so there is nothing for Garmin to have received. Asking anyway would
        // spend a Garmin request to learn nothing, and a stray PRESENT would describe someone
        // else's workout rather than this draft's.
        if (!checkGarmin || !sentToIntervals) {
            return status(draftId, approved, intervalsDelivery, publication,
                GarminDeliveryStatus.GARMIN_NOT_CHECKED, null, 0, null)
        }

        return try {
            val payload = scheduledWorkouts.fetchMonth(YearMonth.from(approved.draft.date))
            val found = reader.workoutsOn(payload, approved.draft.date)
            log.info("Garmin delivery check: draftId={} date={} scheduledWorkouts={}",
                draftId, approved.draft.date, found.size)
            status(draftId, approved, intervalsDelivery, publication,
                if (found.isEmpty()) GarminDeliveryStatus.GARMIN_NOT_FOUND else GarminDeliveryStatus.GARMIN_PRESENT,
                null, found.size, clock.instant())
        } catch (e: GarminConnectorException) {
            // A failed lookup says nothing about delivery, so it is its own state rather than an
            // absence - and it is reported, not thrown: the Intervals half of the answer is still
            // worth having.
            log.warn("Garmin delivery check could not reach Garmin: draftId={} reason={}", draftId, e.reason)
            status(draftId, approved, intervalsDelivery, publication,
                GarminDeliveryStatus.GARMIN_LOOKUP_FAILED, "GARMIN_" + e.reason.name, 0, clock.instant())
        }
    }

    private fun status(
        draftId: Long,
        approved: com.runningai.coach.ApprovedWorkoutDraft,
        intervalsDelivery: IntervalsDeliveryStatus,
        publication: WorkoutDraftPublication?,
        garminDelivery: GarminDeliveryStatus,
        failureCode: String?,
        garminCount: Int,
        checkedAt: Instant?,
    ) = WorkoutDeliveryStatus(
        draftId = draftId,
        date = approved.draft.date,
        workoutType = approved.draft.workoutType,
        intervalsDelivery = intervalsDelivery,
        intervalsEventId = publication?.remoteEventId,
        publishedAt = publication?.publishedAt,
        minutesSincePublish = publication?.publishedAt?.let { Duration.between(it, clock.instant()).toMinutes() },
        garminDelivery = garminDelivery,
        garminLookupFailureCode = failureCode,
        garminScheduledWorkoutCount = garminCount,
        garminCheckedAt = checkedAt,
    )
}

@RestController
@RequestMapping("/api/v1/workout-drafts")
class WorkoutDeliveryStatusController(private val service: WorkoutDeliveryStatusService) {

    /**
     * Where an approved draft actually got to. Read-only everywhere: database for the Intervals
     * half, one Garmin GET for the Garmin half, and no write of any kind.
     *
     * @param checkGarmin false answers from the database alone, with no Garmin request at all
     */
    @GetMapping("/{id}/delivery-status")
    fun deliveryStatus(
        @PathVariable id: Long,
        @RequestParam(defaultValue = "true") checkGarmin: Boolean,
    ): WorkoutDeliveryStatus = service.of(id, checkGarmin)
}

/**
 * The two draft-lifecycle refusals this endpoint can raise, mapped to the SAME codes
 * `DraftPublishExceptionHandler` uses for them. They are repeated rather than shared because
 * sharing would mean `draftpublish` naming this controller, and the dependency must only run the
 * other way; `WorkoutDeliveryStatusApiTest` pins the codes so the two cannot drift apart silently.
 */
@RestControllerAdvice(assignableTypes = [WorkoutDeliveryStatusController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class WorkoutDeliveryStatusExceptionHandler {

    @ExceptionHandler(WorkoutDraftNotFoundException::class)
    fun notFound(e: WorkoutDraftNotFoundException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse.of("WORKOUT_DRAFT_NOT_FOUND", e.message ?: "WORKOUT_DRAFT_NOT_FOUND"))

    @ExceptionHandler(WorkoutDraftNotApprovedException::class)
    fun notApproved(e: WorkoutDraftNotApprovedException): ResponseEntity<ErrorResponse> {
        val code = if (e.status == WorkoutDraftStatus.SUPERSEDED) {
            "WORKOUT_DRAFT_SUPERSEDED"
        } else {
            "WORKOUT_DRAFT_APPROVAL_REQUIRED"
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(code, e.message ?: code))
    }
}
