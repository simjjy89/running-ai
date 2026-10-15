package com.runningai.draftpublish

import com.runningai.athlete.AthleteService
import com.runningai.coach.CoachTestFixtures.draft
import com.runningai.coach.CoachTestFixtures.restDraft
import com.runningai.coach.CoachTestFixtures.segment
import com.runningai.coach.WorkoutDraft
import com.runningai.coach.WorkoutDraftApprovalRepository
import com.runningai.coach.WorkoutDraftRepository
import com.runningai.coach.WorkoutDraftStore
import com.runningai.injury.InjuryReportRepository
import com.runningai.injury.InjuryTestFixtures
import com.runningai.integration.intervals.IntervalsException
import com.runningai.integration.intervals.IntervalsPublishOperation
import com.runningai.integration.intervals.IntervalsPublishResult
import com.runningai.integration.intervals.IntervalsWorkoutClient
import com.runningai.integration.intervals.IntervalsWorkoutPublisher
import com.runningai.training.IntensityClass
import com.runningai.training.SegmentType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Clock
import java.util.UUID

/**
 * Phase 8B (Workstream C): a failed publish must stop vanishing.
 *
 * `workout_draft_publication` stores successes only, on purpose - a failure that wrote a row there
 * would either block the retry or claim a workout exists on Intervals.icu. The cost was that the
 * failure existed solely in the HTTP response of the request that failed, so after an external
 * write went wrong there was nothing in the database to look at. The attempt ledger adds the record
 * without touching that decision.
 *
 * The invariants under test:
 *  - a failure is recorded and still leaves NO publication, so the draft remains publishable;
 *  - a success is recorded and the publication semantics are byte-identical to before;
 *  - an outcome that may or may not have landed remotely is marked uncertain rather than guessed;
 *  - recording stores codes and numbers only, never a message, body or workout text;
 *  - nothing that never reached an approved draft is recorded as an attempt to publish one.
 *
 * No real external write is possible: the publisher and the raw client are mocks and the Intervals
 * base URL points at a closed port.
 */
@SpringBootTest(
    properties = [
        "running-ai.draft-publishing.enabled=true",
        "running-ai.intervals.api-key=",
        "running-ai.intervals.base-url=http://127.0.0.1:9",
    ],
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DraftPublishAttemptLedgerTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var store: WorkoutDraftStore
    @Autowired private lateinit var drafts: WorkoutDraftRepository
    @Autowired private lateinit var approvals: WorkoutDraftApprovalRepository
    @Autowired private lateinit var publications: WorkoutDraftPublicationRepository
    @Autowired private lateinit var attempts: WorkoutDraftPublishAttemptRepository
    @Autowired private lateinit var ledger: WorkoutDraftPublishAttemptStore
    @Autowired private lateinit var injuryReportRepository: InjuryReportRepository
    @Autowired private lateinit var athleteService: AthleteService
    @Autowired private lateinit var clock: Clock

    @MockitoBean private lateinit var publisher: IntervalsWorkoutPublisher
    @MockitoBean private lateinit var intervalsClient: IntervalsWorkoutClient

    @BeforeEach
    fun seedSafeInjuryState() {
        InjuryTestFixtures.seedResolved(injuryReportRepository, athleteService, clock)
    }

    @AfterEach
    fun cleanUp() {
        attempts.deleteAll()
        publications.deleteAll()
        approvals.deleteAll()
        drafts.deleteAll()
        injuryReportRepository.deleteAll()
    }

    // ---- helpers --------------------------------------------------------------------------------

    private fun approved(d: WorkoutDraft = draft()): Long {
        val id = requireNotNull(store.save(d, UUID.randomUUID().toString(), supersede = null).id)
        mockMvc.perform(post("/api/v1/workout-drafts/$id/approve")).andExpect(status().isOk)
        return id
    }

    private fun publish(id: Long) = mockMvc.perform(post("/api/v1/workout-drafts/$id/publish"))

    private fun publisherSucceeds(operation: IntervalsPublishOperation = IntervalsPublishOperation.CREATED) {
        doAnswer { IntervalsPublishResult(operation, "evt-1", true, it.getArgument(0)) }
            .`when`(publisher).publish(any(), any())
    }

    private fun publisherThrows(e: IntervalsException) {
        doThrow(e).`when`(publisher).publish(any(), any())
    }

    private fun only(draftId: Long): WorkoutDraftPublishAttempt = ledger.history(draftId).single()

    // ---- the failure that used to disappear ------------------------------------------------------

    @Test
    fun `a failed publish is recorded as an attempt and still leaves no publication`() {
        val id = approved()
        publisherThrows(IntervalsException(IntervalsException.Reason.AUTH_FAILED, 401, "rejected"))

        publish(id).andExpect(status().isUnauthorized)

        // The pre-8B behaviour, unchanged: no publication, so the draft can be published again.
        assertThat(publications.count()).isZero()
        val attempt = only(id)
        assertThat(attempt.outcome).isEqualTo(DraftPublishAttemptOutcome.FAILED)
        assertThat(attempt.failureCode).isEqualTo("INTERVALS_AUTH_FAILED")
        assertThat(attempt.httpStatus).isEqualTo(401)
        assertThat(attempt.externalWriteAttempted).isTrue()
    }

    @Test
    fun `repeated failures accumulate a readable history rather than overwriting one row`() {
        val id = approved()
        publisherThrows(IntervalsException(IntervalsException.Reason.RATE_LIMITED, 429, "slow down"))

        publish(id).andExpect(status().isTooManyRequests)
        publish(id).andExpect(status().isTooManyRequests)
        publish(id).andExpect(status().isTooManyRequests)

        val history = ledger.history(id)
        assertThat(history).hasSize(3)
        assertThat(history.map { it.outcome }).containsOnly(DraftPublishAttemptOutcome.FAILED)
        // Newest first, so "what happened last" is the first row read.
        assertThat(history.map { it.id }).isSortedAccordingTo(reverseOrder())
    }

    /**
     * The case that actually matters operationally. A timeout means the write may or may not have
     * landed; the ledger must say so instead of implying either. Nothing retries on this signal -
     * the publisher already resolves it by re-listing the date by marker - but a human looking at
     * the history has to be able to see the difference.
     */
    @Test
    fun `an outcome that may or may not have reached Intervals is recorded as uncertain`() {
        val id = approved()
        publisherThrows(IntervalsException(IntervalsException.Reason.TIMEOUT, null, "timed out"))

        publish(id).andExpect(status().isGatewayTimeout)

        val attempt = only(id)
        assertThat(attempt.remoteStateCertain).isFalse()
        assertThat(attempt.externalWriteAttempted).isTrue()
    }

    @Test
    fun `a clean refusal is certain about the remote state because nothing was sent`() {
        val id = approved(
            draft(segments = listOf(segment(SegmentType.MAIN, 40, IntensityClass.EASY, hrMin = 140, hrMax = 150))),
        )

        publish(id).andExpect(status().isUnprocessableEntity)

        val attempt = only(id)
        assertThat(attempt.outcome).isEqualTo(DraftPublishAttemptOutcome.REFUSED)
        assertThat(attempt.failureCode).isEqualTo("UNPUBLISHABLE_DRAFT")
        assertThat(attempt.externalWriteAttempted).isFalse()
        assertThat(attempt.remoteStateCertain).isTrue()
    }

    /** The injury gate stops the publish before any external call; the ledger says which gate. */
    @Test
    fun `an injury-gate refusal is recorded with the same code the API returns`() {
        val id = approved()
        injuryReportRepository.deleteAll()        // no report at all -> REST only

        publish(id).andExpect(status().isUnprocessableEntity)

        val attempt = only(id)
        assertThat(attempt.outcome).isEqualTo(DraftPublishAttemptOutcome.REFUSED)
        assertThat(attempt.failureCode).startsWith("WORKOUT_SAFETY_")
        assertThat(attempt.externalWriteAttempted).isFalse()
        assertThat(publications.count()).isZero()
    }

    // ---- successes ------------------------------------------------------------------------------

    @Test
    fun `a successful publish is recorded alongside the publication, not instead of it`() {
        val id = approved()
        publisherSucceeds()

        publish(id).andExpect(status().isOk)

        assertThat(publications.count()).isEqualTo(1)
        val attempt = only(id)
        assertThat(attempt.outcome).isEqualTo(DraftPublishAttemptOutcome.PUBLISHED)
        assertThat(attempt.intervalsOperation).isEqualTo(IntervalsPublishOperation.CREATED)
        assertThat(attempt.remoteEventId).isEqualTo("evt-1")
        assertThat(attempt.failureCode).isNull()
        assertThat(attempt.remoteStateCertain).isTrue()
    }

    @Test
    fun `a rest day is recorded as an attempt that deliberately sent nothing`() {
        val id = approved(restDraft())

        publish(id).andExpect(status().isOk)

        val attempt = only(id)
        assertThat(attempt.outcome).isEqualTo(DraftPublishAttemptOutcome.SKIPPED_REST_DAY)
        assertThat(attempt.externalWriteAttempted).isFalse()
    }

    /** A re-publish returns the stored result; the ledger has to show it cost no external call. */
    @Test
    fun `a republish is recorded as already published with no external write`() {
        val id = approved()
        publisherSucceeds()
        publish(id).andExpect(status().isOk)

        publish(id).andExpect(status().isOk).andExpect(jsonPath("$.alreadyPublished").value(true))

        val history = ledger.history(id)
        assertThat(history).hasSize(2)
        assertThat(history.first().outcome).isEqualTo(DraftPublishAttemptOutcome.ALREADY_PUBLISHED)
        assertThat(history.first().externalWriteAttempted).isFalse()
        assertThat(publications.count()).isEqualTo(1)
    }

    // ---- what is NOT an attempt ------------------------------------------------------------------

    /**
     * A draft that was never approved was never a candidate for publishing, so recording it would
     * put rows in the ledger that describe nothing that could have happened - the same line the
     * Garmin collection ledger draws for a request rejected before it reached Garmin.
     */
    @Test
    fun `publishing an unapproved draft records no attempt`() {
        val id = requireNotNull(store.save(draft(), UUID.randomUUID().toString(), supersede = null).id)

        publish(id).andExpect(status().isConflict)

        assertThat(attempts.count()).isZero()
    }

    // ---- the API --------------------------------------------------------------------------------

    @Test
    fun `the history endpoint reports the attempts newest first`() {
        val id = approved()
        publisherThrows(IntervalsException(IntervalsException.Reason.RATE_LIMITED, 429, "slow down"))
        publish(id).andExpect(status().isTooManyRequests)
        publisherSucceeds(IntervalsPublishOperation.UPDATED)
        publish(id).andExpect(status().isOk)

        mockMvc.perform(get("/api/v1/workout-drafts/$id/publish-attempts"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.draftId").value(id))
            .andExpect(jsonPath("$.attemptCount").value(2))
            .andExpect(jsonPath("$.attempts[0].outcome").value("PUBLISHED"))
            .andExpect(jsonPath("$.attempts[0].intervalsOperation").value("UPDATED"))
            .andExpect(jsonPath("$.attempts[1].outcome").value("FAILED"))
            .andExpect(jsonPath("$.attempts[1].failureCode").value("INTERVALS_RATE_LIMITED"))
            .andExpect(jsonPath("$.attempts[1].httpStatus").value(429))
    }

    @Test
    fun `the history endpoint is a 404 for a draft that does not exist`() {
        mockMvc.perform(get("/api/v1/workout-drafts/999999/publish-attempts"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("WORKOUT_DRAFT_NOT_FOUND"))
    }

    /**
     * Secrets and payloads must not be able to reach this table. The code is derived from the
     * exception's type and reason, so even an exception whose message carries something sensitive
     * stores only the code.
     */
    @Test
    fun `no part of an exception message is stored`() {
        val id = approved()
        publisherThrows(
            IntervalsException(IntervalsException.Reason.CLIENT_ERROR, 400, "api key abc123 rejected for 6x800m"),
        )

        publish(id).andExpect(status().isBadGateway)

        val attempt = only(id)
        assertThat(attempt.failureCode).isEqualTo("INTERVALS_CLIENT_ERROR")
        assertThat(attempt.toString()).doesNotContain("abc123").doesNotContain("6x800m")
    }
}
