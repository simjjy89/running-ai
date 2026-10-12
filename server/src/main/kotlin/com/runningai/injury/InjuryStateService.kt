package com.runningai.injury

import com.runningai.athlete.AthleteService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/** What the athlete (or a caller on their behalf) reported. Always inserted as a new row. */
data class InjuryReportRequest(
    val bodySite: String?,
    val side: InjurySide?,
    val painPresent: Boolean?,
    val painIntensity: Int?,
    val painOnWalking: Boolean?,
    val painOnStairs: Boolean?,
    val swelling: Boolean?,
    val rangeOfMotionLimited: Boolean?,
    val status: InjuryStatus,
    val notes: String?,
    /** When the described status applies from; defaults to now when the caller does not know. */
    val reportedAt: Instant?,
)

/**
 * Reads and records the default athlete's injury/pain history (Phase 7B-2). A thin, transactional
 * wrapper around [InjuryReportRepository]: it never edits a row, only appends one and reads the
 * latest.
 */
@Service
class InjuryStateService(
    private val repository: InjuryReportRepository,
    private val athleteService: AthleteService,
    private val clock: Clock,
) {

    @Transactional
    fun record(request: InjuryReportRequest): InjuryReport {
        val now = Instant.now(clock)
        val athleteId = athleteService.getDefaultAthlete().id
        val entity = InjuryReportEntity(
            athleteId = athleteId,
            bodySite = request.bodySite?.takeIf(String::isNotBlank),
            side = request.side,
            painPresent = request.painPresent,
            painIntensity = request.painIntensity,
            painOnWalking = request.painOnWalking,
            painOnStairs = request.painOnStairs,
            swelling = request.swelling,
            rangeOfMotionLimited = request.rangeOfMotionLimited,
            status = request.status,
            notes = request.notes?.takeIf(String::isNotBlank),
            reportedAt = request.reportedAt ?: now,
            recordedAt = now,
        )
        return repository.save(entity).toDomain()
    }

    /** The default athlete's most recently recorded report, or null when none has ever been made. */
    @Transactional(readOnly = true)
    fun current(): InjuryReport? {
        val athleteId = athleteService.getDefaultAthlete().id
        return repository.findFirstByAthleteIdOrderByRecordedAtDesc(athleteId)?.toDomain()
    }

    /** Full history for the default athlete, newest first. */
    @Transactional(readOnly = true)
    fun history(): List<InjuryReport> {
        val athleteId = athleteService.getDefaultAthlete().id
        return repository.findByAthleteIdOrderByRecordedAtDesc(athleteId).map { it.toDomain() }
    }
}
