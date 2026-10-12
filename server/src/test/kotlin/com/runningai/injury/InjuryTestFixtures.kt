package com.runningai.injury

import com.runningai.athlete.AthleteService
import java.time.Clock
import java.time.Instant

/**
 * Shared seeding for tests that exercise non-REST workout generation/publishing and therefore need
 * a fresh, explicit RESOLVED injury report on file - without one, [WorkoutSafetyPolicy] blocks any
 * non-REST result by design (Phase 7B-2: absence of data is never treated as "no injury").
 */
object InjuryTestFixtures {

    fun seedResolved(repository: InjuryReportRepository, athleteService: AthleteService, clock: Clock) {
        val now = Instant.now(clock)
        repository.save(
            InjuryReportEntity(
                athleteId = athleteService.getDefaultAthlete().id,
                bodySite = null,
                side = null,
                painPresent = false,
                painIntensity = null,
                painOnWalking = false,
                painOnStairs = false,
                swelling = false,
                rangeOfMotionLimited = false,
                status = InjuryStatus.RESOLVED,
                notes = "test fixture: no known injury",
                reportedAt = now,
                recordedAt = now,
            ),
        )
    }

    fun seedActive(
        repository: InjuryReportRepository,
        athleteService: AthleteService,
        clock: Clock,
        painOnWalking: Boolean,
    ) {
        val now = Instant.now(clock)
        repository.save(
            InjuryReportEntity(
                athleteId = athleteService.getDefaultAthlete().id,
                bodySite = "knee",
                side = InjurySide.LEFT,
                painPresent = true,
                painIntensity = null,
                painOnWalking = painOnWalking,
                painOnStairs = null,
                swelling = null,
                rangeOfMotionLimited = null,
                status = InjuryStatus.ACTIVE,
                notes = "test fixture: reported pain",
                reportedAt = now,
                recordedAt = now,
            ),
        )
    }
}
