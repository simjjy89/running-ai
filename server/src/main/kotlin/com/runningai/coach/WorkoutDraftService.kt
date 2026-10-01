package com.runningai.coach

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.runningai.athlete.AthleteService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

/** The requested draft does not exist. */
class WorkoutDraftNotFoundException(id: Long) : RuntimeException("Workout draft $id was not found")

/** A revision was requested against a draft that has already been superseded by a newer version. */
class WorkoutDraftSupersededException(id: Long, val currentVersion: Int) :
    RuntimeException("Workout draft $id has been superseded by version $currentVersion; revise the latest version")

/**
 * Transaction boundary for draft persistence.
 *
 * Separate from [WorkoutDraftService] on purpose: the AI call takes seconds and must not run inside
 * a database transaction, and a `@Transactional` method called from within the same bean would not
 * be proxied at all. Keeping the transactional work in its own bean makes both correct.
 */
@Service
class WorkoutDraftStore(
    private val repository: WorkoutDraftRepository,
    private val athleteService: AthleteService,
    private val objectMapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(WorkoutDraftStore::class.java)

    /**
     * Inserts [draft] as the current version of [draftGroupId] and, when [supersede] is given,
     * marks that earlier row [WorkoutDraftStatus.SUPERSEDED] in the same transaction — so a draft
     * group can never end up with two current versions or lose its predecessor.
     */
    @Transactional
    fun save(draft: WorkoutDraft, draftGroupId: String, supersede: Long?): WorkoutDraft {
        supersede?.let { previousId ->
            repository.findById(previousId).ifPresent { it.status = WorkoutDraftStatus.SUPERSEDED }
        }
        val entity = WorkoutDraftEntity(
            athleteId = athleteService.getDefaultAthlete().id,
            draftGroupId = draftGroupId,
            version = draft.version,
            workoutDate = draft.date,
            status = WorkoutDraftStatus.DRAFT,
            title = draft.title,
            workoutType = draft.workoutType,
            totalDurationMinutes = draft.totalDurationMinutes,
            recoveryAssessment = draft.assessment.recoveryAssessment,
            loadAssessment = draft.assessment.loadAssessment,
            selectedWorkoutType = draft.assessment.selectedWorkoutType,
            rationale = draft.assessment.rationale,
            warnings = objectMapper.valueToTree(draft.assessment.warnings),
            segments = objectMapper.valueToTree(draft.segments),
            provider = draft.provider,
            model = draft.model,
        )
        val saved = repository.save(entity)
        log.info(
            "Workout draft saved: id={} group={} version={} date={} type={} status=DRAFT",
            saved.id, saved.draftGroupId, saved.version, saved.workoutDate, saved.workoutType,
        )
        return toDomain(saved)
    }

    @Transactional(readOnly = true)
    fun get(id: Long): WorkoutDraft =
        toDomain(repository.findById(id).orElseThrow { WorkoutDraftNotFoundException(id) })

    /**
     * Loads a draft for revision, refusing an already-superseded version rather than forking the
     * history: a draft group always has exactly one current version.
     */
    @Transactional(readOnly = true)
    fun loadForRevision(id: Long): WorkoutDraft {
        val existing = repository.findById(id).orElseThrow { WorkoutDraftNotFoundException(id) }
        if (existing.status == WorkoutDraftStatus.SUPERSEDED) {
            val latest = repository.findByDraftGroupIdOrderByVersionDesc(existing.draftGroupId).first()
            throw WorkoutDraftSupersededException(id, latest.version)
        }
        return toDomain(existing)
    }

    private fun toDomain(e: WorkoutDraftEntity): WorkoutDraft = WorkoutDraft(
        id = e.id,
        draftGroupId = e.draftGroupId,
        version = e.version,
        date = e.workoutDate,
        title = e.title,
        workoutType = e.workoutType,
        totalDurationMinutes = e.totalDurationMinutes,
        assessment = CoachAssessment(
            recoveryAssessment = e.recoveryAssessment,
            loadAssessment = e.loadAssessment,
            selectedWorkoutType = e.selectedWorkoutType,
            rationale = e.rationale,
            warnings = e.warnings
                ?.let { objectMapper.convertValue(it, object : TypeReference<List<String>>() {}) }
                ?: emptyList(),
        ),
        segments = objectMapper.convertValue(e.segments, object : TypeReference<List<WorkoutDraftSegment>>() {}),
        provider = e.provider,
        model = e.model,
        status = e.status,
        createdAt = e.createdAt,
    )
}

/**
 * Generates and revises AI-coach workout drafts.
 *
 * It orchestrates context -> coach -> store and owns no training logic: a revision is a full
 * redesign by the [AiCoach] against the original context plus the athlete's own words. Spring never
 * mechanically shortens a session or lowers an intensity, because that would produce a workout no
 * coach actually prescribed.
 *
 * It has, and must keep, **no dependency on any publishing, Intervals or Garmin type** (enforced by
 * an architecture test): producing a draft can never cause a publish, whatever
 * `WORKOUT_PUBLISHING_ENABLED` is set to.
 */
@Service
class WorkoutDraftService(
    private val coach: AiCoach,
    private val contextBuilder: TrainingContextBuilder,
    private val store: WorkoutDraftStore,
) {

    fun today(): LocalDate = contextBuilder.today()

    fun generate(date: LocalDate, constraints: SessionConstraints): WorkoutDraft {
        val context = contextBuilder.build(date, constraints)
        val designed = coach.createWorkout(context)
        return store.save(designed, UUID.randomUUID().toString(), supersede = null)
    }

    fun get(id: Long): WorkoutDraft = store.get(id)

    fun revise(id: Long, userRequest: String, constraints: SessionConstraints? = null): WorkoutDraft {
        val current = store.loadForRevision(id)
        val context = contextBuilder.build(current.date, constraints ?: SessionConstraints())
        val revised = coach.reviseWorkout(context, current, userRequest)
        return store.save(revised, requireNotNull(current.draftGroupId), supersede = current.id)
    }
}
