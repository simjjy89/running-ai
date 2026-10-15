package com.runningai.activity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * Phase 8B (Workstream F): telling the coach what was never measured.
 *
 * RunningAI's history comes from three sources that carry different things. Before this, an
 * imported activity simply had no cadence field - and an omitted metric reads to a coach as a bad
 * value, which is the exact opposite of the truth. These absences are properties of the import,
 * decided once and documented in the two importer architecture notes, so the mapping is asserted
 * against those decisions rather than re-derived from data.
 */
class MetricAvailabilityTest {

    @Test
    fun `a Garmin activity is missing nothing structurally`() {
        assertThat(MetricAvailability.unavailableFor(ExternalSource.GARMIN)).isEmpty()
    }

    /**
     * Intervals.icu streams carry one-leg rpm, no splits, and a 7-zone HR model against Garmin's 5;
     * the engine reads dynamics from laps, which these have none of.
     */
    @Test
    fun `an Intervals-imported activity is missing everything that import could not carry`() {
        assertThat(MetricAvailability.unavailableFor(ExternalSource.INTERVALS_ICU))
            .containsExactlyInAnyOrder(
                UnavailableMetric.CADENCE,
                UnavailableMetric.RUNNING_DYNAMICS,
                UnavailableMetric.LAP_STRUCTURE,
                UnavailableMetric.INTERVAL_GROUPS,
                UnavailableMetric.HEART_RATE_ZONES,
            )
    }

    /**
     * Strava's re-encoded FIT does restore laps - what it cannot carry is `intensity` and
     * `wkt_step_index`, so which laps formed a repeat block is unknowable and inferring it from
     * pace is banned.
     */
    @Test
    fun `a Strava-imported activity keeps its laps but not its interval groups`() {
        val unavailable = MetricAvailability.unavailableFor(ExternalSource.STRAVA)

        assertThat(unavailable).contains(UnavailableMetric.INTERVAL_GROUPS)
        assertThat(unavailable).doesNotContain(UnavailableMetric.LAP_STRUCTURE)
    }

    /** Both importers refuse the rpm-to-spm conversion, so both lose cadence. */
    @ParameterizedTest
    @EnumSource(value = ExternalSource::class, names = ["INTERVALS_ICU", "STRAVA"])
    fun `no imported source reports cadence`(source: ExternalSource) {
        assertThat(MetricAvailability.unavailableFor(source)).contains(UnavailableMetric.CADENCE)
    }

    @Test
    fun `an all-Garmin history produces no notes at all`() {
        assertThat(MetricAvailability.notesFor(List(5) { ExternalSource.GARMIN })).isEmpty()
    }

    @Test
    fun `a note counts the activities it applies to and states the reason once`() {
        val notes = MetricAvailability.notesFor(
            listOf(ExternalSource.GARMIN, ExternalSource.STRAVA, ExternalSource.INTERVALS_ICU),
        )

        val cadence = notes.single { it.metric == UnavailableMetric.CADENCE }
        assertThat(cadence.affectedActivityCount).isEqualTo(2)
        // Not repeated per activity: the snapshot is size-capped at 64 KiB.
        assertThat(notes.count { it.metric == UnavailableMetric.CADENCE }).isEqualTo(1)
    }

    @Test
    fun `only metrics that something actually lacks are reported`() {
        val notes = MetricAvailability.notesFor(listOf(ExternalSource.STRAVA))

        assertThat(notes.map { it.metric })
            .containsExactlyInAnyOrder(
                UnavailableMetric.CADENCE,
                UnavailableMetric.RUNNING_DYNAMICS,
                UnavailableMetric.INTERVAL_GROUPS,
            )
    }

    /**
     * The whole point is that the coach must not read these as assessments. A reason that contained
     * "poor", "low" or "declining" would reintroduce exactly the misreading this exists to prevent.
     */
    @Test
    fun `no reason reads as a judgement about the athlete`() {
        val judgementWords = listOf("poor", "low", "bad", "weak", "declin", "worse", "insufficient", "should")

        val notes = MetricAvailability.notesFor(listOf(ExternalSource.INTERVALS_ICU, ExternalSource.STRAVA))

        assertThat(notes).isNotEmpty
        notes.forEach { note ->
            val reason = note.reason.lowercase()
            judgementWords.forEach {
                assertThat(reason).withFailMessage(
                    "the reason for %s reads as a judgement (%s): %s", note.metric, it, note.reason,
                ).doesNotContain(it)
            }
        }
    }

    @Test
    fun `every metric has a reason, so a new one cannot be added silently without an explanation`() {
        val notes = MetricAvailability.notesFor(listOf(ExternalSource.INTERVALS_ICU, ExternalSource.STRAVA))

        assertThat(notes.map { it.metric }).containsExactlyInAnyOrderElementsOf(UnavailableMetric.entries)
        assertThat(notes).allSatisfy { assertThat(it.reason).isNotBlank() }
    }

    @Test
    fun `an unknown or absent source is treated as missing nothing rather than guessed at`() {
        assertThat(MetricAvailability.unavailableFor(null)).isEmpty()
    }
}
