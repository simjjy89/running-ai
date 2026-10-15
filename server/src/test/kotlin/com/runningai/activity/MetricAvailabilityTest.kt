package com.runningai.activity
import com.runningai.activity.MetricAvailability.ActivityMetricSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * Phase 8B (Workstream F): telling the coach what was never measured.
 *
 * RunningAI's history comes from three sources that carry different things. Before this, an
 * imported activity simply had no cadence field - and an omitted metric reads to a coach as a bad
 * value, which is the exact opposite of the truth.
 *
 * Most of these absences are properties of the import, decided once and documented in the two
 * importer architecture notes, so the mapping is asserted against those decisions rather than
 * re-derived from data. [UnavailableMetric.LAP_STRUCTURE] is the exception and is asserted from
 * the data instead, because no source can answer it - see the tests that say so below.
 */
class MetricAvailabilityTest {

    private fun garmin(hasLaps: Boolean = true) = ActivityMetricSource(ExternalSource.GARMIN, hasLaps)
    private fun intervals(hasLaps: Boolean) = ActivityMetricSource(ExternalSource.INTERVALS_ICU, hasLaps)
    private fun strava(hasLaps: Boolean = true) = ActivityMetricSource(ExternalSource.STRAVA, hasLaps)

    @Test
    fun `a Garmin activity with its laps collected is missing nothing structurally`() {
        assertThat(MetricAvailability.unavailableFor(ExternalSource.GARMIN, hasLapStructure = true)).isEmpty()
    }

    /**
     * Intervals.icu streams carry one-leg rpm, no splits, and a 7-zone HR model against Garmin's 5;
     * the engine reads dynamics from laps, which these have none of.
     */
    @Test
    fun `an Intervals-imported activity is missing everything that import could not carry`() {
        assertThat(MetricAvailability.unavailableFor(ExternalSource.INTERVALS_ICU, hasLapStructure = false))
            .containsExactlyInAnyOrder(
                UnavailableMetric.CADENCE,
                UnavailableMetric.RUNNING_DYNAMICS,
                UnavailableMetric.LAP_STRUCTURE,
                UnavailableMetric.INTERVAL_GROUPS,
                UnavailableMetric.HEART_RATE_ZONES,
            )
    }

    /**
     * The defect this replaced: `external_source` cannot answer whether laps exist, because the
     * Strava archive import (Phase 7E-2) writes laps INTO activities imported from Intervals.icu.
     * Measured on the real history, every one of the 25 Intervals-sourced activities carries laps,
     * 67 of them written by that importer - and all 25 were being reported to the coach as having
     * no per-lap breakdown at all.
     */
    @Test
    fun `an Intervals activity the Strava archive gave laps to is not reported as having none`() {
        val unavailable = MetricAvailability.unavailableFor(ExternalSource.INTERVALS_ICU, hasLapStructure = true)

        assertThat(unavailable).doesNotContain(UnavailableMetric.LAP_STRUCTURE)
        // What the archive could NOT restore is still reported: its laps carry no `intensity` and
        // no `wkt_step_index`, so the repeat blocks stay underivable.
        assertThat(unavailable).contains(UnavailableMetric.INTERVAL_GROUPS)
    }

    /** The absence is a fact about the rows, so it is reported for any source that has no laps. */
    @Test
    fun `a Garmin activity whose detail was never collected is reported as having no lap breakdown`() {
        assertThat(MetricAvailability.unavailableFor(ExternalSource.GARMIN, hasLapStructure = false))
            .containsExactly(UnavailableMetric.LAP_STRUCTURE)
    }

    /**
     * Strava's re-encoded FIT does restore laps - what it cannot carry is `intensity` and
     * `wkt_step_index`, so which laps formed a repeat block is unknowable and inferring it from
     * pace is banned.
     */
    @Test
    fun `a Strava-imported activity keeps its laps but not its interval groups`() {
        val unavailable = MetricAvailability.unavailableFor(ExternalSource.STRAVA, hasLapStructure = true)

        assertThat(unavailable).contains(UnavailableMetric.INTERVAL_GROUPS)
        assertThat(unavailable).doesNotContain(UnavailableMetric.LAP_STRUCTURE)
    }

    /** Both importers refuse the rpm-to-spm conversion, so both lose cadence. */
    @ParameterizedTest
    @EnumSource(value = ExternalSource::class, names = ["INTERVALS_ICU", "STRAVA"])
    fun `no imported source reports cadence`(source: ExternalSource) {
        assertThat(MetricAvailability.unavailableFor(source, hasLapStructure = true))
            .contains(UnavailableMetric.CADENCE)
    }

    /**
     * Neither importer writes `activity_zone` - Intervals because its 7-zone model is not mapped
     * onto Garmin's 5, Strava because the archive has no zone table at all. Strava was omitted
     * here, so a coach reading an empty zone distribution for one of those 15 activities was not
     * told the zones had never been collected.
     */
    @ParameterizedTest
    @EnumSource(value = ExternalSource::class, names = ["INTERVALS_ICU", "STRAVA"])
    fun `no imported source reports time-in-zone`(source: ExternalSource) {
        assertThat(MetricAvailability.unavailableFor(source, hasLapStructure = true))
            .contains(UnavailableMetric.HEART_RATE_ZONES)
    }

    @Test
    fun `an all-Garmin history with laps produces no notes at all`() {
        assertThat(MetricAvailability.notesFor(List(5) { garmin() })).isEmpty()
    }

    @Test
    fun `a note counts the activities it applies to and states the reason once`() {
        val notes = MetricAvailability.notesFor(listOf(garmin(), strava(), intervals(hasLaps = true)))

        val cadence = notes.single { it.metric == UnavailableMetric.CADENCE }
        assertThat(cadence.affectedActivityCount).isEqualTo(2)
        // Not repeated per activity: the snapshot is size-capped at 64 KiB.
        assertThat(notes.count { it.metric == UnavailableMetric.CADENCE }).isEqualTo(1)
    }

    @Test
    fun `only metrics that something actually lacks are reported`() {
        val notes = MetricAvailability.notesFor(listOf(strava()))

        assertThat(notes.map { it.metric })
            .containsExactlyInAnyOrder(
                UnavailableMetric.CADENCE,
                UnavailableMetric.RUNNING_DYNAMICS,
                UnavailableMetric.INTERVAL_GROUPS,
                UnavailableMetric.HEART_RATE_ZONES,
            )
    }

    /** Only the activities that really lack laps are counted, not every imported one. */
    @Test
    fun `the lap note counts the activities with no laps, not the imported ones`() {
        val notes = MetricAvailability.notesFor(
            listOf(intervals(hasLaps = true), intervals(hasLaps = true), intervals(hasLaps = false)),
        )

        assertThat(notes.single { it.metric == UnavailableMetric.LAP_STRUCTURE }.affectedActivityCount)
            .isEqualTo(1)
        assertThat(notes.single { it.metric == UnavailableMetric.CADENCE }.affectedActivityCount)
            .isEqualTo(3)
    }

    /**
     * The whole point is that the coach must not read these as assessments. A reason that contained
     * "poor", "low" or "declining" would reintroduce exactly the misreading this exists to prevent.
     */
    @Test
    fun `no reason reads as a judgement about the athlete`() {
        val judgementWords = listOf("poor", "low", "bad", "weak", "declin", "worse", "insufficient", "should")

        val notes = MetricAvailability.notesFor(listOf(intervals(hasLaps = false), strava()))

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
        val notes = MetricAvailability.notesFor(listOf(intervals(hasLaps = false), strava()))

        assertThat(notes.map { it.metric }).containsExactlyInAnyOrderElementsOf(UnavailableMetric.entries)
        assertThat(notes).allSatisfy { assertThat(it.reason).isNotBlank() }
    }

    @Test
    fun `an unknown or absent source with laps is treated as missing nothing rather than guessed at`() {
        assertThat(MetricAvailability.unavailableFor(null, hasLapStructure = true)).isEmpty()
    }
}
