package com.runningai.coach

import com.runningai.coach.CoachTestFixtures.draft
import com.runningai.coach.CoachTestFixtures.restDraft
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.time.LocalDate
import java.util.UUID

/**
 * Approval lifecycle against the real schema (H2 + the same Flyway migrations). Not `@Transactional`:
 * the status change, the approval row and the database constraints must be observed as committed.
 */
@SpringBootTest(properties = ["running-ai.intervals.api-key=", "running-ai.intervals.base-url=http://127.0.0.1:9"])
@ActiveProfiles("test")
class WorkoutDraftApprovalTest {

    @Autowired private lateinit var store: WorkoutDraftStore
    @Autowired private lateinit var approvalService: WorkoutDraftApprovalService
    @Autowired private lateinit var approvals: WorkoutDraftApprovalRepository
    @Autowired private lateinit var repository: WorkoutDraftRepository
    @Autowired private lateinit var jdbc: JdbcTemplate

    @AfterEach
    fun cleanUp() {
        jdbc.update("delete from workout_draft_publication")
        approvals.deleteAll()
        repository.deleteAll()
    }

    private fun saved(d: WorkoutDraft = draft()): WorkoutDraft = store.save(d, UUID.randomUUID().toString(), supersede = null)

    @Test
    fun `a current draft is approved and the approval is recorded in the same step`() {
        val d = saved()

        val approved = approvalService.approve(d.id!!)

        assertThat(approved.draft.status).isEqualTo(WorkoutDraftStatus.APPROVED)
        assertThat(store.get(d.id!!).status).isEqualTo(WorkoutDraftStatus.APPROVED)
        assertThat(approved.approval.draftId).isEqualTo(d.id)
        assertThat(approved.approval.date).isEqualTo(d.date)
        assertThat(approved.approval.approvedAt).isNotNull
        assertThat(approvals.findByDraftId(d.id!!)).isNotNull
        // nothing about the workout itself changed
        assertThat(approved.draft.segments).isEqualTo(d.segments)
        assertThat(approved.draft.totalDurationMinutes).isEqualTo(d.totalDurationMinutes)
    }

    @Test
    fun `approving the same draft again is idempotent`() {
        val d = saved()
        val first = approvalService.approve(d.id!!)

        val second = approvalService.approve(d.id!!)

        assertThat(second.approval).isEqualTo(first.approval)
        assertThat(second.draft.status).isEqualTo(WorkoutDraftStatus.APPROVED)
        assertThat(approvals.count()).isEqualTo(1)
    }

    @Test
    fun `a superseded version cannot be approved`() {
        val group = UUID.randomUUID().toString()
        val v1 = store.save(draft(version = 1), group, supersede = null)
        store.save(draft(version = 2), group, supersede = v1.id)

        assertThatThrownBy { approvalService.approve(v1.id!!) }
            .isInstanceOfSatisfying(WorkoutDraftSupersededException::class.java) {
                assertThat(it.currentVersion).isEqualTo(2)
            }
        assertThat(approvals.count()).isZero()
    }

    @Test
    fun `an unknown draft cannot be approved`() {
        assertThatThrownBy { approvalService.approve(987_654_321L) }
            .isInstanceOf(WorkoutDraftNotFoundException::class.java)
    }

    @Test
    fun `an approved draft cannot be loaded for revision`() {
        val d = saved()
        approvalService.approve(d.id!!)

        assertThatThrownBy { store.loadForRevision(d.id!!) }
            .isInstanceOf(WorkoutDraftApprovedImmutableException::class.java)
            .isNotInstanceOf(WorkoutDraftSupersededException::class.java)
    }

    @Test
    fun `a revision started before the approval cannot supersede the approved draft`() {
        // The AI call between loadForRevision and save takes seconds; the draft may be approved meanwhile.
        val group = UUID.randomUUID().toString()
        val v1 = store.save(draft(version = 1), group, supersede = null)
        store.loadForRevision(v1.id!!)
        approvalService.approve(v1.id!!)

        assertThatThrownBy { store.save(draft(version = 2), group, supersede = v1.id) }
            .isInstanceOf(WorkoutDraftApprovedImmutableException::class.java)

        assertThat(store.get(v1.id!!).status).isEqualTo(WorkoutDraftStatus.APPROVED)
        assertThat(repository.findByDraftGroupIdOrderByVersionDesc(group)).hasSize(1)
    }

    @Test
    fun `a second draft for the same athlete and date cannot be approved`() {
        val a = saved(draft(title = "Draft A"))
        val b = saved(draft(title = "Draft B"))
        approvalService.approve(a.id!!)

        assertThatThrownBy { approvalService.approve(b.id!!) }
            .isInstanceOfSatisfying(WorkoutDateAlreadyApprovedException::class.java) {
                assertThat(it.approvedDraftId).isEqualTo(a.id)
            }

        assertThat(store.get(b.id!!).status).isEqualTo(WorkoutDraftStatus.DRAFT)
        assertThat(approvals.count()).isEqualTo(1)
    }

    @Test
    fun `drafts for different dates are approved independently`() {
        val a = saved(draft(date = LocalDate.of(2026, 10, 3)))
        val b = saved(draft(date = LocalDate.of(2026, 10, 4)))

        approvalService.approve(a.id!!)
        approvalService.approve(b.id!!)

        assertThat(approvals.count()).isEqualTo(2)
    }

    @Test
    fun `a rest day is approved like any other draft`() {
        val d = saved(restDraft())

        val approved = approvalService.approve(d.id!!)

        assertThat(approved.draft.isRest).isTrue()
        assertThat(approved.draft.status).isEqualTo(WorkoutDraftStatus.APPROVED)
    }

    @Test
    fun `findApproved returns null for a draft that is not approved`() {
        val d = saved()

        assertThat(approvalService.findApproved(d.id!!)).isNull()
        approvalService.approve(d.id!!)
        assertThat(approvalService.findApproved(d.id!!)!!.approval.draftId).isEqualTo(d.id)
    }

    // ---- database-level guarantees, independent of the service --------------------------------

    @Test
    fun `the database refuses two approvals for one athlete and date`() {
        val a = saved()
        val b = saved()
        val athleteId = repository.findById(a.id!!).get().athleteId
        val insert = "insert into workout_draft_approval (draft_id, athlete_id, workout_date, approved_at) " +
            "values (?, ?, ?, current_timestamp)"
        jdbc.update(insert, a.id, athleteId, a.date)

        assertThatThrownBy { jdbc.update(insert, b.id, athleteId, b.date) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `the database refuses two approvals of one draft`() {
        val a = saved()
        val athleteId = repository.findById(a.id!!).get().athleteId
        val insert = "insert into workout_draft_approval (draft_id, athlete_id, workout_date, approved_at) " +
            "values (?, ?, ?, current_timestamp)"
        jdbc.update(insert, a.id, athleteId, a.date)

        assertThatThrownBy { jdbc.update(insert, a.id, athleteId, a.date.plusDays(1)) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }
}
