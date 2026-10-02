package com.runningai.coach

import com.fasterxml.jackson.databind.JsonNode
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.time.LocalDate

/**
 * Stored form of a [WorkoutDraft]. Never exposed outside this package: callers see the domain
 * model or a DTO, following the project's entity-is-not-an-API rule.
 *
 * Only [status] is ever mutated after insert (DRAFT -> SUPERSEDED when a newer version replaces
 * it, DRAFT -> APPROVED on explicit approval); everything else is write-once, which is what makes the version history an audit trail.
 */
@Entity
@Table(name = "workout_draft")
@EntityListeners(AuditingEntityListener::class)
class WorkoutDraftEntity(

    @Column(name = "athlete_id", nullable = false)
    var athleteId: Long,

    @Column(name = "draft_group_id", nullable = false, length = 64)
    var draftGroupId: String,

    @Column(name = "version", nullable = false)
    var version: Int,

    @Column(name = "workout_date", nullable = false)
    var workoutDate: LocalDate,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    var status: WorkoutDraftStatus,

    @Column(name = "title", nullable = false, length = 200)
    var title: String,

    @Column(name = "workout_type", nullable = false, length = 64)
    var workoutType: String,

    @Column(name = "total_duration_minutes", nullable = false)
    var totalDurationMinutes: Int,

    @Column(name = "recovery_assessment", nullable = false)
    var recoveryAssessment: String,

    @Column(name = "load_assessment", nullable = false)
    var loadAssessment: String,

    @Column(name = "selected_workout_type", nullable = false, length = 64)
    var selectedWorkoutType: String,

    @Column(name = "rationale", nullable = false)
    var rationale: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "warnings")
    var warnings: JsonNode?,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "segments", nullable = false)
    var segments: JsonNode,

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 32)
    var provider: CoachProvider,

    @Column(name = "model", length = 128)
    var model: String?,
) {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        private set

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null
        private set

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null
        private set
}

interface WorkoutDraftRepository : JpaRepository<WorkoutDraftEntity, Long> {

    fun findByDraftGroupIdOrderByVersionDesc(draftGroupId: String): List<WorkoutDraftEntity>

    /**
     * Compare-and-set status transition: changes the row only if it is still in [from] and returns
     * the number of rows changed (0 or 1). A concurrent approve and revise of the same draft both
     * want to leave `DRAFT`; the row lock makes exactly one of them win and the other see 0.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update WorkoutDraftEntity d set d.status = :to, d.updatedAt = :now " +
            "where d.id = :id and d.status = :from",
    )
    fun transitionStatus(
        @Param("id") id: Long,
        @Param("from") from: WorkoutDraftStatus,
        @Param("to") to: WorkoutDraftStatus,
        @Param("now") now: Instant,
    ): Int

    fun findFirstByAthleteIdAndWorkoutDateAndStatusOrderByVersionDesc(
        athleteId: Long,
        workoutDate: LocalDate,
        status: WorkoutDraftStatus,
    ): WorkoutDraftEntity?
}
