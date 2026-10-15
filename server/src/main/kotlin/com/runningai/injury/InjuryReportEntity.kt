package com.runningai.injury

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

/**
 * Stored form of an [InjuryReport]. Append-only: every field is set once at construction and never
 * reassigned afterwards, which is what makes the row history a trustworthy audit trail (Phase
 * 7B-2). A changed status is always a *new* row, never an edit of an old one.
 */
@Entity
@Table(name = "injury_report")
class InjuryReportEntity(

    @Column(name = "athlete_id", nullable = false)
    val athleteId: Long,

    @Column(name = "body_site", length = 64)
    val bodySite: String?,

    @Enumerated(EnumType.STRING)
    @Column(name = "side", length = 16)
    val side: InjurySide?,

    @Column(name = "pain_present")
    val painPresent: Boolean?,

    @Column(name = "pain_intensity")
    val painIntensity: Int?,

    @Column(name = "pain_on_walking")
    val painOnWalking: Boolean?,

    @Column(name = "pain_on_stairs")
    val painOnStairs: Boolean?,

    @Column(name = "swelling")
    val swelling: Boolean?,

    @Column(name = "range_of_motion_limited")
    val rangeOfMotionLimited: Boolean?,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    val status: InjuryStatus,

    @Column(name = "notes", length = 2000)
    val notes: String?,

    @Column(name = "reported_at", nullable = false)
    val reportedAt: Instant,

    @Column(name = "recorded_at", nullable = false)
    val recordedAt: Instant,
) {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        private set

    fun toDomain() = InjuryReport(
        id = id,
        athleteId = athleteId,
        bodySite = bodySite,
        side = side,
        painPresent = painPresent,
        painIntensity = painIntensity,
        painOnWalking = painOnWalking,
        painOnStairs = painOnStairs,
        swelling = swelling,
        rangeOfMotionLimited = rangeOfMotionLimited,
        status = status,
        notes = notes,
        reportedAt = reportedAt,
        recordedAt = recordedAt,
    )
}

interface InjuryReportRepository : JpaRepository<InjuryReportEntity, Long> {

    /**
     * The athlete's current status: the most recently recorded report, or null when none exists.
     *
     * `recorded_at` alone does not order this ledger. It is written as `Instant.now(clock)`, and two
     * reports appended inside one clock tick - a retried shell pipeline, a test using a fixed clock -
     * share it exactly, leaving the database free to return either one as "latest". For an append-only
     * safety ledger that is not acceptable: which row is latest decides whether the gate permits
     * training. `id DESC` breaks the tie by insertion order, which is the only correct answer when the
     * timestamps are equal, and changes nothing when they differ (same precedent as
     * `AthleteIntensityProfileHistoryRepository`).
     */
    fun findFirstByAthleteIdOrderByRecordedAtDescIdDesc(athleteId: Long): InjuryReportEntity?

    /**
     * Full history, newest first. Rows are never updated or deleted, so this is the whole ledger.
     * Tie-broken on `id DESC` for the same reason as above - and additionally so the coach's
     * `previousReports` list and `omittedReportCount` cap are stable across calls.
     */
    fun findByAthleteIdOrderByRecordedAtDescIdDesc(athleteId: Long): List<InjuryReportEntity>
}
