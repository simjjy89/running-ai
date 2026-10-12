package com.runningai.injury

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * The decision table itself (Phase 7B-2 STEP C), with no database and no Spring context: every
 * branch of [WorkoutSafetyPolicy.evaluate] against a hand-built [InjuryReport] or its absence.
 */
class WorkoutSafetyPolicyTest {

    private val now = Instant.parse("2026-10-13T00:00:00Z")
    private val policy = WorkoutSafetyPolicy(InjurySafetyProperties(stalenessHours = 48))

    private fun report(
        status: InjuryStatus,
        recordedAt: Instant = now,
        painOnWalking: Boolean? = null,
        bodySite: String? = "knee",
    ) = InjuryReport(
        id = 1, athleteId = 1, bodySite = bodySite, side = InjurySide.LEFT,
        painPresent = status == InjuryStatus.ACTIVE, painIntensity = null,
        painOnWalking = painOnWalking, painOnStairs = null, swelling = null, rangeOfMotionLimited = null,
        status = status, notes = null, reportedAt = recordedAt, recordedAt = recordedAt,
    )

    @Test
    fun `no report at all blocks non-REST training`() {
        val decision = policy.evaluate(null, now)

        assertThat(decision.nonRestTrainingAllowed).isFalse()
        assertThat(decision.reasonCode).isEqualTo(InjurySafetyReasonCode.NO_INJURY_STATE_RECORDED)
    }

    @Test
    fun `an ACTIVE report with gait pain blocks non-REST training`() {
        val decision = policy.evaluate(report(InjuryStatus.ACTIVE, painOnWalking = true), now)

        assertThat(decision.nonRestTrainingAllowed).isFalse()
        assertThat(decision.reasonCode).isEqualTo(InjurySafetyReasonCode.INJURY_ACTIVE)
    }

    @Test
    fun `an ACTIVE report without gait pain still blocks non-REST training (never auto-cleared)`() {
        val decision = policy.evaluate(report(InjuryStatus.ACTIVE, painOnWalking = false), now)

        assertThat(decision.nonRestTrainingAllowed).isFalse()
        assertThat(decision.reasonCode).isEqualTo(InjurySafetyReasonCode.INJURY_ACTIVE)
    }

    @Test
    fun `UNKNOWN status blocks non-REST training`() {
        val decision = policy.evaluate(report(InjuryStatus.UNKNOWN), now)

        assertThat(decision.nonRestTrainingAllowed).isFalse()
        assertThat(decision.reasonCode).isEqualTo(InjurySafetyReasonCode.INJURY_STATE_UNKNOWN)
    }

    @Test
    fun `a fresh explicit RESOLVED report allows non-REST training`() {
        val decision = policy.evaluate(report(InjuryStatus.RESOLVED, recordedAt = now.minus(Duration.ofHours(1))), now)

        assertThat(decision.nonRestTrainingAllowed).isTrue()
        assertThat(decision.reasonCode).isEqualTo(InjurySafetyReasonCode.INJURY_RESOLVED_CONFIRMED)
    }

    @Test
    fun `a RESOLVED report older than the freshness window is treated as stale, not as still clear`() {
        val staleResolved = report(InjuryStatus.RESOLVED, recordedAt = now.minus(Duration.ofHours(49)))

        val decision = policy.evaluate(staleResolved, now)

        assertThat(decision.nonRestTrainingAllowed).isFalse()
        assertThat(decision.reasonCode).isEqualTo(InjurySafetyReasonCode.INJURY_STATE_STALE)
    }

    @Test
    fun `an ACTIVE report older than the freshness window is still blocked, just for the staleness reason`() {
        val staleActive = report(InjuryStatus.ACTIVE, recordedAt = now.minus(Duration.ofHours(72)), painOnWalking = true)

        val decision = policy.evaluate(staleActive, now)

        assertThat(decision.nonRestTrainingAllowed).isFalse()
        assertThat(decision.reasonCode).isEqualTo(InjurySafetyReasonCode.INJURY_STATE_STALE)
    }

    @Test
    fun `a report exactly at the freshness boundary is still fresh`() {
        val boundary = report(InjuryStatus.RESOLVED, recordedAt = now.minus(Duration.ofHours(48)))

        val decision = policy.evaluate(boundary, now)

        assertThat(decision.nonRestTrainingAllowed).isTrue()
    }

    @Test
    fun `the staleness window is configurable`() {
        val shortWindow = WorkoutSafetyPolicy(InjurySafetyProperties(stalenessHours = 1))
        val resolvedTwoHoursAgo = report(InjuryStatus.RESOLVED, recordedAt = now.minus(Duration.ofHours(2)))

        val decision = shortWindow.evaluate(resolvedTwoHoursAgo, now)

        assertThat(decision.nonRestTrainingAllowed).isFalse()
        assertThat(decision.reasonCode).isEqualTo(InjurySafetyReasonCode.INJURY_STATE_STALE)
    }
}
