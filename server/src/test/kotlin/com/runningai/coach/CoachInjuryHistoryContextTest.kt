package com.runningai.coach

import com.runningai.athlete.AthleteService
import com.runningai.injury.InjuryReportEntity
import com.runningai.injury.InjuryReportRepository
import com.runningai.injury.InjurySafetyReasonCode
import com.runningai.injury.InjurySide
import com.runningai.injury.InjuryStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * What the AI coach is actually told about the athlete's injury history (Phase 7F-3A), against the
 * real schema, the real [com.runningai.injury.InjuryStateService] and the real Phase 7B-2
 * [com.runningai.injury.WorkoutSafetyPolicy].
 *
 * The property this suite exists for: a RESOLVED report silences the safety constraint
 * (`constraintText` returns null by design), and before 7F-3A that left the coach designing a
 * session with no idea the athlete had been injured days earlier. Every state is checked, not only
 * the one that was broken, so a later change cannot quietly drop one.
 *
 * It asserts nothing about enforcement: the gate is unchanged and is covered by
 * [WorkoutDraftSafetyGateTest], which still proves the server blocks a non-REST draft on its own.
 */
@SpringBootTest(
    properties = [
        "running-ai.garmin.connector.base-url=http://127.0.0.1:9",
        "running-ai.intervals.api-key=",
        "running-ai.intervals.base-url=http://127.0.0.1:9",
        "running-ai.coach.injury-history.history-window-days=30",
        "running-ai.coach.injury-history.max-previous-reports=2",
        "running-ai.coach.injury-history.max-notes-excerpt-chars=20",
    ],
)
@ActiveProfiles("test")
class CoachInjuryHistoryContextTest {

    @Autowired private lateinit var builder: InjuryHistoryContextBuilder
    @Autowired private lateinit var v1: TrainingContextBuilder
    @Autowired private lateinit var v2: TrainingContextV2Builder
    @Autowired private lateinit var serializer: CoachContextSerializer
    @Autowired private lateinit var reports: InjuryReportRepository
    @Autowired private lateinit var athleteService: AthleteService

    @AfterEach
    fun cleanUp() = reports.deleteAll()

    /** Inserts a report directly, so an arbitrary age can be set without moving the system clock. */
    private fun report(
        status: InjuryStatus,
        ageHours: Long = 0,
        bodySite: String? = "knee",
        side: InjurySide? = null,
        painPresent: Boolean? = null,
        painIntensity: Int? = null,
        painOnWalking: Boolean? = null,
        notes: String? = null,
    ) {
        val at = Instant.now().minus(Duration.ofHours(ageHours))
        reports.save(
            InjuryReportEntity(
                athleteId = athleteService.getDefaultAthlete().id,
                bodySite = bodySite, side = side, painPresent = painPresent, painIntensity = painIntensity,
                painOnWalking = painOnWalking, painOnStairs = null, swelling = null,
                rangeOfMotionLimited = null, status = status, notes = notes,
                reportedAt = at, recordedAt = at,
            ),
        )
    }

    // ---------------------------------------------------------------- state by state

    @Test
    fun `no injury report has ever been made`() {
        val history = builder.build()

        assertThat(history.totalReportCount).isZero()
        assertThat(history.latest).isNull()
        assertThat(history.previousReports).isEmpty()
        assertThat(history.omittedReportCount).isZero()
        assertThat(history.daysSinceLatestActiveReport).isNull()
        assertThat(history.gate.nonRestTrainingAllowed).isFalse()
        assertThat(history.gate.reasonCode).isEqualTo(InjurySafetyReasonCode.NO_INJURY_STATE_RECORDED)
    }

    @Test
    fun `an ACTIVE report is carried with its symptoms and blocks non-REST training`() {
        report(
            InjuryStatus.ACTIVE, bodySite = "knee", side = InjurySide.LEFT,
            painPresent = true, painIntensity = 4, painOnWalking = true,
        )

        val history = builder.build()
        val latest = requireNotNull(history.latest)

        assertThat(latest.status).isEqualTo(InjuryStatus.ACTIVE)
        assertThat(latest.bodySite).isEqualTo("knee")
        assertThat(latest.side).isEqualTo(InjurySide.LEFT)
        assertThat(latest.painPresent).isTrue()
        assertThat(latest.painIntensity).isEqualTo(4)
        assertThat(latest.painOnWalking).isTrue()
        assertThat(latest.ageHours).isZero()
        assertThat(history.daysSinceLatestActiveReport).isZero()
        assertThat(history.gate.nonRestTrainingAllowed).isFalse()
        assertThat(history.gate.reasonCode).isEqualTo(InjurySafetyReasonCode.INJURY_ACTIVE)
    }

    @Test
    fun `an UNKNOWN report is carried as UNKNOWN, never as an all-clear`() {
        report(InjuryStatus.UNKNOWN)

        val history = builder.build()

        assertThat(requireNotNull(history.latest).status).isEqualTo(InjuryStatus.UNKNOWN)
        assertThat(history.gate.nonRestTrainingAllowed).isFalse()
        assertThat(history.gate.reasonCode).isEqualTo(InjurySafetyReasonCode.INJURY_STATE_UNKNOWN)
        // An UNKNOWN status is not an ACTIVE report, so it never fabricates a return-to-training date.
        assertThat(history.daysSinceLatestActiveReport).isNull()
    }

    @Test
    fun `a fresh RESOLVED report still reaches the coach, which is the whole point of this context`() {
        report(InjuryStatus.RESOLVED, bodySite = "knee", side = InjurySide.LEFT, painPresent = false)

        val history = builder.build()
        val latest = requireNotNull(history.latest)

        // The gate permits training and therefore says nothing in `constraintText`...
        assertThat(history.gate.nonRestTrainingAllowed).isTrue()
        assertThat(history.gate.reasonCode).isEqualTo(InjurySafetyReasonCode.INJURY_RESOLVED_CONFIRMED)
        // ...but the report itself is still handed to the coach, in full structured form.
        assertThat(latest.status).isEqualTo(InjuryStatus.RESOLVED)
        assertThat(latest.bodySite).isEqualTo("knee")
        assertThat(latest.painPresent).isFalse()
        assertThat(history.totalReportCount).isEqualTo(1)
    }

    @Test
    fun `a stale report is reported with its real age, not dropped or refreshed`() {
        report(InjuryStatus.RESOLVED, ageHours = 72)

        val history = builder.build()
        val latest = requireNotNull(history.latest)

        assertThat(history.gate.nonRestTrainingAllowed).isFalse()
        assertThat(history.gate.reasonCode).isEqualTo(InjurySafetyReasonCode.INJURY_STATE_STALE)
        assertThat(history.gate.stalenessHours).isEqualTo(48)
        assertThat(latest.status).isEqualTo(InjuryStatus.RESOLVED)
        assertThat(latest.ageHours).isEqualTo(72)
    }

    @Test
    fun `a RESOLVED report after a recent ACTIVE one shows the sequence and the days since the injury`() {
        report(InjuryStatus.ACTIVE, ageHours = 73, painOnWalking = true) // ~3 days ago
        report(InjuryStatus.RESOLVED, ageHours = 1, painPresent = false)

        val history = builder.build()

        assertThat(requireNotNull(history.latest).status).isEqualTo(InjuryStatus.RESOLVED)
        assertThat(history.previousReports).hasSize(1)
        assertThat(history.previousReports.single().status).isEqualTo(InjuryStatus.ACTIVE)
        assertThat(history.previousReports.single().painOnWalking).isTrue()
        // Training is permitted, and the coach is simultaneously told this is a return from an
        // injury reported three days ago. The RESOLVED row did not erase the ACTIVE one.
        assertThat(history.gate.nonRestTrainingAllowed).isTrue()
        assertThat(history.daysSinceLatestActiveReport).isEqualTo(3)
    }

    @Test
    fun `an item the athlete did not report stays null rather than reading as no symptom`() {
        report(
            InjuryStatus.RESOLVED, bodySite = null, side = null,
            painPresent = null, painIntensity = null, painOnWalking = null,
        )

        val latest = requireNotNull(builder.build().latest)

        assertThat(latest.bodySite).isNull()
        assertThat(latest.side).isNull()
        assertThat(latest.painPresent).isNull()
        assertThat(latest.painIntensity).isNull()
        assertThat(latest.painOnWalking).isNull()
        assertThat(latest.painOnStairs).isNull()
        assertThat(latest.swelling).isNull()
        assertThat(latest.rangeOfMotionLimited).isNull()
        assertThat(latest.notesPresent).isFalse()
        assertThat(latest.notesExcerpt).isNull()
    }

    // ---------------------------------------------------------------- caps and sensitive text

    @Test
    fun `a long note is capped and flagged, and the part beyond the cap never reaches the snapshot`() {
        val secret = "0123456789012345678901234567890123456789 PRIVATE-TAIL-NOT-FOR-THE-PROMPT"
        report(InjuryStatus.RESOLVED, notes = secret)

        val history = builder.build()
        val latest = requireNotNull(history.latest)

        assertThat(latest.notesPresent).isTrue()
        assertThat(latest.notesExcerpt).isEqualTo(secret.take(20))
        assertThat(latest.notesTruncated).isTrue()

        val json = serializer.snapshot(
            CoachTestFixtures.context().copy(injuryHistory = history),
            Instant.parse("2026-10-14T00:00:00Z"),
        ).json
        assertThat(json).doesNotContain("PRIVATE-TAIL-NOT-FOR-THE-PROMPT")
    }

    @Test
    fun `only the latest report carries free text at all`() {
        report(InjuryStatus.ACTIVE, ageHours = 10, notes = "OLDER-NOTE-BODY")
        report(InjuryStatus.RESOLVED, ageHours = 1, notes = "latest note")

        val history = builder.build()

        assertThat(requireNotNull(history.latest).notesExcerpt).isEqualTo("latest note")
        assertThat(history.previousReports.single().notesPresent).isTrue()
        assertThat(history.previousReports.single().notesExcerpt).isNull()
    }

    @Test
    fun `history is capped, and what was left out is counted rather than hidden`() {
        repeat(5) { report(InjuryStatus.RESOLVED, ageHours = (it + 2).toLong()) }
        report(InjuryStatus.RESOLVED, ageHours = 1)

        val history = builder.build()

        assertThat(history.totalReportCount).isEqualTo(6)
        assertThat(history.reportsInWindow).isEqualTo(6)
        assertThat(history.previousReports).hasSize(2) // max-previous-reports=2
        assertThat(history.omittedReportCount).isEqualTo(3) // 6 - (1 latest + 2 listed)
        assertThat(history.historyWindowDays).isEqualTo(30)
    }

    @Test
    fun `an ACTIVE report older than the listing window is still reported as the injury being returned from`() {
        report(InjuryStatus.ACTIVE, ageHours = 24 * 60, painOnWalking = true) // 60 days: outside the 30-day window
        report(InjuryStatus.RESOLVED, ageHours = 1)

        val history = builder.build()

        assertThat(history.totalReportCount).isEqualTo(2)
        assertThat(history.reportsInWindow).isEqualTo(1)
        assertThat(history.previousReports).isEmpty()
        assertThat(history.omittedReportCount).isEqualTo(1)
        // Computed over the whole ledger on purpose: a flare-up that scrolled out of the listing is
        // still what the athlete is coming back from.
        assertThat(history.daysSinceLatestActiveReport).isEqualTo(60)
    }

    // ---------------------------------------------------------------- both context versions

    @Test
    fun `both production context builders populate the injury history`() {
        report(InjuryStatus.RESOLVED)
        val date = LocalDate.of(2026, 10, 14)

        val v1History = v1.build(date, SessionConstraints()).injuryHistory
        val v2History = v2.build(date, SessionConstraints()).injuryHistory

        assertThat(v1History).isNotNull
        assertThat(v2History).isNotNull
        assertThat(requireNotNull(v1History).latest?.status).isEqualTo(InjuryStatus.RESOLVED)
        assertThat(requireNotNull(v2History).latest?.status).isEqualTo(InjuryStatus.RESOLVED)
    }

    @Test
    fun `the injury history survives serialization into the context snapshot the coach is sent`() {
        report(InjuryStatus.ACTIVE, ageHours = 50, painOnWalking = true)
        report(InjuryStatus.RESOLVED, ageHours = 2)

        val context = v1.build(LocalDate.of(2026, 10, 14), SessionConstraints())
        val json = serializer.snapshot(context, Instant.parse("2026-10-14T00:00:00Z")).json

        assertThat(json).contains("\"injuryHistory\"")
        assertThat(json).contains("\"daysSinceLatestActiveReport\":2")
        assertThat(json).contains("INJURY_RESOLVED_CONFIRMED")
    }
}
