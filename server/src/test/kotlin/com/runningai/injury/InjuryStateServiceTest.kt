package com.runningai.injury

import com.runningai.athlete.AthleteService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

/**
 * Append-only storage against the real schema (H2 + the same Flyway migrations). Not
 * `@Transactional`: a changed status must be observed as a genuinely new, separately committed row.
 */
@SpringBootTest(properties = ["running-ai.intervals.api-key=", "running-ai.intervals.base-url=http://127.0.0.1:9"])
@ActiveProfiles("test")
class InjuryStateServiceTest {

    @Autowired private lateinit var service: InjuryStateService
    @Autowired private lateinit var repository: InjuryReportRepository
    @Autowired private lateinit var athleteService: AthleteService

    @AfterEach
    fun cleanUp() = repository.deleteAll()

    @Test
    fun `a report is saved and read back as the current state`() {
        val saved = service.record(
            InjuryReportRequest(
                bodySite = "knee", side = InjurySide.LEFT, painPresent = true, painIntensity = 3,
                painOnWalking = true, painOnStairs = false, swelling = false, rangeOfMotionLimited = false,
                status = InjuryStatus.ACTIVE, notes = "lateral knee pain after a race", reportedAt = null,
            ),
        )

        assertThat(saved.id).isNotNull()
        assertThat(saved.athleteId).isEqualTo(athleteService.getDefaultAthlete().id)
        assertThat(saved.recordedAt).isNotNull()

        // H2 (test profile) rounds a TIMESTAMP WITH TIME ZONE to microseconds on round-trip, unlike
        // the in-memory value's native nanosecond precision - compare everything else exactly, the
        // two Instant fields only to millisecond precision.
        val current = requireNotNull(service.current())
        assertThat(current).usingRecursiveComparison()
            .withComparatorForFields({ a, b -> (a as java.time.Instant).truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
                .compareTo((b as java.time.Instant).truncatedTo(java.time.temporal.ChronoUnit.MILLIS)) }, "reportedAt", "recordedAt")
            .isEqualTo(saved)
    }

    @Test
    fun `an absent history returns no current report`() {
        assertThat(service.current()).isNull()
        assertThat(service.history()).isEmpty()
    }

    @Test
    fun `a new report never overwrites the previous one - the full history is preserved`() {
        service.record(InjuryReportRequest(null, null, true, null, true, null, null, null, InjuryStatus.ACTIVE, "first", null))
        service.record(InjuryReportRequest(null, null, null, null, null, null, null, null, InjuryStatus.RESOLVED, "second", null))

        val history = service.history()

        assertThat(history).hasSize(2)
        assertThat(history.map { it.notes }).containsExactly("second", "first") // newest first
        assertThat(service.current()!!.status).isEqualTo(InjuryStatus.RESOLVED)
    }

    /**
     * Phase 9 (E-1). `recordedAt` is `Instant.now(clock)`, so two reports appended inside one clock
     * tick carry a byte-identical timestamp - the real production case was a retried shell pipeline
     * writing two rows 0.5 s apart, and a fixed test clock collapses that to 0. Ordering on
     * `recorded_at DESC` alone then leaves the database free to return either row as "latest",
     * which for a safety ledger decides whether training is permitted.
     *
     * The rows are saved directly rather than through [InjuryStateService.record] because the
     * service stamps the real clock and so cannot be made to produce a genuine tie.
     */
    @Test
    fun `reports sharing one recordedAt are ordered by insertion, newest first`() {
        val sameInstant = java.time.Instant.parse("2026-10-15T00:00:00Z")
        val athleteId = athleteService.getDefaultAthlete().id
        val older = repository.save(tiedReport(athleteId, sameInstant, InjuryStatus.ACTIVE, "older"))
        val newer = repository.save(tiedReport(athleteId, sameInstant, InjuryStatus.RESOLVED, "newer"))

        assertThat(newer.id!!).isGreaterThan(older.id!!)
        // Repeated so an ordering that merely happens to come out right once is not mistaken for a
        // defined one; with the tie-break in place every read is identical.
        repeat(5) {
            assertThat(service.history().map { r -> r.notes }).containsExactly("newer", "older")
            assertThat(service.current()!!.id).isEqualTo(newer.id)
            assertThat(service.current()!!.status).isEqualTo(InjuryStatus.RESOLVED)
        }
    }

    private fun tiedReport(athleteId: Long, at: java.time.Instant, status: InjuryStatus, notes: String) =
        InjuryReportEntity(
            athleteId = athleteId, bodySite = null, side = null, painPresent = null, painIntensity = null,
            painOnWalking = null, painOnStairs = null, swelling = null, rangeOfMotionLimited = null,
            status = status, notes = notes, reportedAt = at, recordedAt = at,
        )
}
