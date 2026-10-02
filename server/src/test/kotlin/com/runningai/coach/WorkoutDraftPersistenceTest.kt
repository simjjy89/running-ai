package com.runningai.coach

import com.runningai.coach.CoachTestFixtures.draft
import com.runningai.coach.CoachTestFixtures.segment
import com.runningai.training.IntensityClass
import com.runningai.training.SegmentType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

/**
 * Draft storage and version history against the real schema (H2 with the same Flyway migrations).
 * Not `@Transactional`: the version/supersede behaviour must be observed as actually committed.
 */
@SpringBootTest(properties = ["running-ai.intervals.api-key=", "running-ai.intervals.base-url=http://127.0.0.1:9"])
@ActiveProfiles("test")
class WorkoutDraftPersistenceTest {

    @Autowired private lateinit var store: WorkoutDraftStore
    @Autowired private lateinit var repository: WorkoutDraftRepository

    @AfterEach
    fun cleanUp() = repository.deleteAll()

    @Test
    fun `a saved draft round-trips through the database unchanged`() {
        val segments = listOf(
            segment(SegmentType.WARM_UP, 10, IntensityClass.VERY_EASY),
            segment(SegmentType.MAIN, 3, IntensityClass.HARD, paceFast = 270, paceSlow = 285,
                hrMin = 170, hrMax = 180, repetitions = 5, recoveryMinutes = 2,
                speedMin = 11.0, speedMax = 12.0, inclineMin = 0.5, inclineMax = 1.5),
            segment(SegmentType.COOL_DOWN, 10, IntensityClass.VERY_EASY),
        )
        val original = draft(
            segments = segments,
            totalDurationMinutes = 45,
            assessment = CoachTestFixtures.assessment(warnings = listOf("Watch the achilles")),
        )

        val saved = store.save(original, UUID.randomUUID().toString(), supersede = null)
        val reloaded = store.get(requireNotNull(saved.id))

        assertThat(reloaded.title).isEqualTo(original.title)
        assertThat(reloaded.totalDurationMinutes).isEqualTo(45)
        assertThat(reloaded.status).isEqualTo(WorkoutDraftStatus.DRAFT)
        assertThat(reloaded.provider).isEqualTo(CoachProvider.CLAUDE)
        assertThat(reloaded.createdAt).isNotNull
        assertThat(reloaded.assessment.warnings).containsExactly("Watch the achilles")
        assertThat(reloaded.segments).hasSize(3)
        // every optional target survives the JSON round trip
        val main = reloaded.segments[1]
        assertThat(main.paceSecondsPerKmFast).isEqualTo(270)
        assertThat(main.heartRateBpmMax).isEqualTo(180)
        assertThat(main.treadmillSpeedKphMin).isEqualTo(11.0)
        assertThat(main.inclinePercentMax).isEqualTo(1.5)
        assertThat(main.repetitions).isEqualTo(5)
        assertThat(main.recoveryDurationMinutes).isEqualTo(2)
        assertThat(main.effectiveDurationMinutes).isEqualTo(25)
    }

    @Test
    fun `saving a revision supersedes the previous version instead of overwriting it`() {
        val group = UUID.randomUUID().toString()
        val v1 = store.save(draft(version = 1, title = "First"), group, supersede = null)

        val v2 = store.save(draft(version = 2, title = "Second"), group, supersede = v1.id)

        assertThat(v2.version).isEqualTo(2)
        assertThat(v2.status).isEqualTo(WorkoutDraftStatus.DRAFT)
        assertThat(store.get(requireNotNull(v1.id)).status).isEqualTo(WorkoutDraftStatus.SUPERSEDED)
        // the old version is kept, not deleted: the history is the audit trail
        assertThat(store.get(requireNotNull(v1.id)).title).isEqualTo("First")
        assertThat(repository.findByDraftGroupIdOrderByVersionDesc(group)).hasSize(2)
    }

    @Test
    fun `all versions of one draft share a draft group id`() {
        val group = UUID.randomUUID().toString()
        val v1 = store.save(draft(version = 1), group, supersede = null)
        val v2 = store.save(draft(version = 2), group, supersede = v1.id)

        assertThat(v1.draftGroupId).isEqualTo(group)
        assertThat(v2.draftGroupId).isEqualTo(group)
    }

    @Test
    fun `revising a superseded version is refused rather than forking the history`() {
        val group = UUID.randomUUID().toString()
        val v1 = store.save(draft(version = 1), group, supersede = null)
        store.save(draft(version = 2), group, supersede = v1.id)

        assertThatThrownBy { store.loadForRevision(requireNotNull(v1.id)) }
            .isInstanceOfSatisfying(WorkoutDraftSupersededException::class.java) {
                assertThat(it.currentVersion).isEqualTo(2)
            }
    }

    @Test
    fun `the current version can still be revised`() {
        val group = UUID.randomUUID().toString()
        val v1 = store.save(draft(version = 1), group, supersede = null)

        val loaded = store.loadForRevision(requireNotNull(v1.id))

        assertThat(loaded.version).isEqualTo(1)
        assertThat(loaded.status).isEqualTo(WorkoutDraftStatus.DRAFT)
    }

    @Test
    fun `an unknown draft id is reported as not found`() {
        assertThatThrownBy { store.get(987_654_321L) }
            .isInstanceOf(WorkoutDraftNotFoundException::class.java)
    }

    @Test
    fun `a duplicate version within one group is rejected by the unique constraint`() {
        val group = UUID.randomUUID().toString()
        store.save(draft(version = 1), group, supersede = null)

        assertThatThrownBy { store.save(draft(version = 1), group, supersede = null) }
            .isInstanceOf(Exception::class.java)
    }

    @Test
    fun `drafts in different groups do not interfere`() {
        val a = store.save(draft(version = 1, title = "Group A"), UUID.randomUUID().toString(), supersede = null)
        val b = store.save(draft(version = 1, title = "Group B"), UUID.randomUUID().toString(), supersede = null)

        assertThat(store.get(requireNotNull(a.id)).status).isEqualTo(WorkoutDraftStatus.DRAFT)
        assertThat(store.get(requireNotNull(b.id)).status).isEqualTo(WorkoutDraftStatus.DRAFT)
        assertThat(a.draftGroupId).isNotEqualTo(b.draftGroupId)
    }

    // ---- REST (Phase 6F.1) --------------------------------------------------------------------

    @Test
    fun `a rest day is stored and read back as 0 minutes with no segments`() {
        val saved = store.save(CoachTestFixtures.restDraft(), UUID.randomUUID().toString(), supersede = null)
        val reloaded = store.get(requireNotNull(saved.id))

        assertThat(reloaded.workoutType).isEqualTo("REST")
        assertThat(reloaded.isRest).isTrue()
        assertThat(reloaded.totalDurationMinutes).isZero()
        assertThat(reloaded.segments).isEmpty()
        assertThat(reloaded.status).isEqualTo(WorkoutDraftStatus.DRAFT)
        assertThat(reloaded.assessment.selectedWorkoutType).isEqualTo("REST")
        assertThat(reloaded.assessment.warnings).isNotEmpty
    }

    @Test
    fun `a rest day is superseded by an easy revision`() {
        val group = UUID.randomUUID().toString()
        val v1 = store.save(CoachTestFixtures.restDraft(version = 1), group, supersede = null)

        val v2 = store.save(draft(version = 2, title = "Easy 30"), group, supersede = v1.id)

        assertThat(store.get(requireNotNull(v1.id)).status).isEqualTo(WorkoutDraftStatus.SUPERSEDED)
        assertThat(store.get(requireNotNull(v1.id)).isRest).isTrue()
        assertThat(store.get(requireNotNull(v2.id)).status).isEqualTo(WorkoutDraftStatus.DRAFT)
        assertThat(store.get(requireNotNull(v2.id)).workoutType).isEqualTo("EASY")
    }

    @Test
    fun `an easy draft can be revised into a rest day`() {
        val group = UUID.randomUUID().toString()
        val v1 = store.save(draft(version = 1), group, supersede = null)

        val v2 = store.save(CoachTestFixtures.restDraft(version = 2), group, supersede = v1.id)

        assertThat(store.get(requireNotNull(v1.id)).status).isEqualTo(WorkoutDraftStatus.SUPERSEDED)
        val current = store.get(requireNotNull(v2.id))
        assertThat(current.status).isEqualTo(WorkoutDraftStatus.DRAFT)
        assertThat(current.isRest).isTrue()
        assertThat(current.segments).isEmpty()
        assertThat(repository.findByDraftGroupIdOrderByVersionDesc(group).map { it.version }).containsExactly(2, 1)
    }

    @Test
    fun `the schema itself refuses a padded rest day and a zero minute workout`() {
        val group = UUID.randomUUID().toString()

        assertThatThrownBy {
            store.save(CoachTestFixtures.restDraft().copy(totalDurationMinutes = 5), group, supersede = null)
        }.isInstanceOf(Exception::class.java)
        assertThatThrownBy {
            store.save(draft(totalDurationMinutes = 0), UUID.randomUUID().toString(), supersede = null)
        }.isInstanceOf(Exception::class.java)
        assertThat(repository.count()).isZero()
    }
}
