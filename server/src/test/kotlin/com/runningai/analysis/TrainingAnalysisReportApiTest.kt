package com.runningai.analysis

import com.runningai.activity.Activity
import com.runningai.activity.ActivityRepository
import com.runningai.activity.ActivityType
import com.runningai.activity.ExternalSource
import com.runningai.athlete.AthleteService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.time.Instant

/**
 * Phase 8B (Workstream F): a descriptive report over the stored history.
 *
 * The thing worth guarding is restraint. This report aggregates data an athlete will read about
 * themselves, which makes every rounding, every default and every absent value an opportunity to
 * assert something untrue - so the tests below are mostly about what it refuses to say: no
 * judgement words, no best effort that is not actually a stored activity of that distance, no
 * trend built from one half of the history being empty, no zero standing in for "unknown", and no
 * analysis triggered by a read.
 */
@SpringBootTest(
    properties = [
        "running-ai.garmin.connector.base-url=http://127.0.0.1:9",
        "running-ai.intervals.api-key=",
        "running-ai.intervals.base-url=http://127.0.0.1:9",
    ],
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TrainingAnalysisReportApiTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var activities: ActivityRepository
    @Autowired private lateinit var athletes: AthleteService
    @Autowired private lateinit var jdbc: JdbcTemplate

    private val base: Instant = Instant.parse("2026-06-01T21:30:00Z")

    @AfterEach
    fun cleanUp() {
        listOf(
            "activity_analysis_interval", "activity_analysis_interval_group", "activity_analysis",
            "activity_detail_collection", "activity_sample", "activity_zone", "activity_lap",
            "activity_detail", "activity_raw_payload", "activity_raw",
            "activity_intervals_metrics", "activity_source_link", "activity",
        ).forEach { jdbc.update("delete from $it") }
    }

    private fun run(
        dayOffset: Long,
        distanceMeters: Double?,
        durationSeconds: Int,
        source: ExternalSource = ExternalSource.GARMIN,
        type: ActivityType = ActivityType.RUN,
    ): Long = activities.save(
        Activity(
            athletes.getDefaultAthlete().id, source, "report-$source-$dayOffset-$durationSeconds", type,
            base.plus(Duration.ofDays(dayOffset)), durationSeconds, distanceMeters, 150, 170,
        ),
    ).id

    private fun report(periods: Int? = null) = mockMvc.perform(
        get("/api/v1/training-analysis-report").apply { if (periods != null) param("periods", periods.toString()) },
    )

    // ---- coverage --------------------------------------------------------------------------------

    @Test
    fun `an empty history is an empty report, not an error`() {
        report()
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.coverage.activityCount").value(0))
            .andExpect(jsonPath("$.coverage.oldestActivityDate").doesNotExist())
            .andExpect(jsonPath("$.coverage.historySpanDays").doesNotExist())
            .andExpect(jsonPath("$.volume").isEmpty)
    }

    @Test
    fun `coverage reports the span and the source mix, because the sources carry different metrics`() {
        run(0, 10000.0, 3000)
        run(30, 10000.0, 3000, source = ExternalSource.STRAVA)
        run(60, 10000.0, 3000, source = ExternalSource.INTERVALS_ICU)

        report()
            .andExpect(jsonPath("$.coverage.activityCount").value(3))
            .andExpect(jsonPath("$.coverage.historySpanDays").value(61))
            .andExpect(jsonPath("$.coverage.activityCountBySource.GARMIN").value(1))
            .andExpect(jsonPath("$.coverage.activityCountBySource.STRAVA").value(1))
            .andExpect(jsonPath("$.coverage.activityCountBySource.INTERVALS_ICU").value(1))
    }

    /** A cycling session is not a run and must not be averaged into running numbers. */
    @Test
    fun `non-running activities are counted but never measured as runs`() {
        run(0, 10000.0, 3000)
        run(1, 30000.0, 3600, type = ActivityType.INDOOR_CYCLING)

        report()
            .andExpect(jsonPath("$.coverage.activityCount").value(2))
            .andExpect(jsonPath("$.coverage.runningActivityCount").value(1))
    }

    // ---- volume ----------------------------------------------------------------------------------

    @Test
    fun `volume is reported in equal-length slices, oldest first`() {
        run(0, 10000.0, 3000)
        run(40, 12000.0, 3600)

        report(periods = 2)
            .andExpect(status().isOk)
            // Exactly the requested number of slices: the span is divided with a ceiling, so no
            // short remainder slice appears at the end to look like a drop in volume.
            .andExpect(jsonPath("$.volume.length()").value(2))
            .andExpect(jsonPath("$.volume[0].activityCount").value(1))
            .andExpect(jsonPath("$.volume[0].totalDistanceKm").value(10.0))
            .andExpect(jsonPath("$.volume[1].totalDistanceKm").value(12.0))
    }

    /** "No activity recorded a distance" and "0 km was run" are different claims. */
    @Test
    fun `a slice whose activities carry no distance reports null, never zero`() {
        run(0, null, 3000)

        report(periods = 1)
            .andExpect(jsonPath("$.volume[0].activityCount").value(1))
            .andExpect(jsonPath("$.volume[0].totalDistanceKm").doesNotExist())
            .andExpect(jsonPath("$.volume[0].longestRunKm").doesNotExist())
    }

    @Test
    fun `the slice count is clamped rather than trusted`() {
        run(0, 10000.0, 3000)
        run(100, 10000.0, 3000)

        report(periods = 1000).andExpect(status().isOk)
        report(periods = 0).andExpect(status().isOk)
    }

    // ---- best efforts ----------------------------------------------------------------------------

    @Test
    fun `the fastest activity of about a standard distance is reported with its date`() {
        run(0, 10000.0, 3000)        // 5:00 / km
        run(10, 10050.0, 2700)       // 4:28 / km - faster, and within tolerance of 10 km

        report()
            .andExpect(jsonPath("$.bestEfforts[?(@.label == '10K')].matchingActivityCount").value(2))
            .andExpect(jsonPath("$.bestEfforts[?(@.label == '10K')].fastestDurationSeconds").value(2700))
    }

    /** Compared by pace: a slightly longer run finishing later is not the slower effort. */
    @Test
    fun `efforts are compared by pace, not by elapsed time`() {
        run(0, 9800.0, 2900)         // 4:56 / km
        run(10, 10200.0, 2950)       // 4:49 / km - longer AND longer-lasting, but faster

        report()
            .andExpect(jsonPath("$.bestEfforts[?(@.label == '10K')].fastestDurationSeconds").value(2950))
    }

    /** A fast old run must never be presentable as current form. */
    @Test
    fun `the most recent matching effort is reported beside the fastest`() {
        run(0, 10000.0, 2700)        // the fastest, months ago
        run(90, 10000.0, 3300)       // the most recent, slower

        report()
            .andExpect(jsonPath("$.bestEfforts[?(@.label == '10K')].fastestPaceSecondsPerKm").value(270))
            .andExpect(jsonPath("$.bestEfforts[?(@.label == '10K')].mostRecentPaceSecondsPerKm").value(330))
    }

    @Test
    fun `a distance with nothing near it reports no effort rather than the nearest run`() {
        run(0, 10000.0, 3000)

        report()
            .andExpect(jsonPath("$.bestEfforts[?(@.label == 'MARATHON')].matchingActivityCount").value(0))
            // Indexed, not filtered: a filter yields a one-element list holding null, which reads
            // as "present and empty" rather than "no effort". The order is fixed in the service.
            .andExpect(jsonPath("$.bestEfforts[3].label").value("MARATHON"))
            .andExpect(jsonPath("$.bestEfforts[3].fastestDate").doesNotExist())
            .andExpect(jsonPath("$.bestEfforts[3].fastestPaceSecondsPerKm").doesNotExist())
    }

    @Test
    fun `a run outside the tolerance is not counted towards a standard distance`() {
        run(0, 15000.0, 4500)        // 15 km must not land in the 10 km bucket

        report()
            .andExpect(jsonPath("$.bestEfforts[?(@.label == '10K')].matchingActivityCount").value(0))
    }

    // ---- trends ----------------------------------------------------------------------------------

    @Test
    fun `a metric no activity carries is reported as not comparable, never as zero change`() {
        run(0, 10000.0, 3000)

        report()
            .andExpect(jsonPath("$.durability.speedHrDecouplingPercent.sampleCount").value(0))
            .andExpect(jsonPath("$.durability.speedHrDecouplingPercent.comparable").value(false))
            .andExpect(jsonPath("$.durability.speedHrDecouplingPercent.change").doesNotExist())
            .andExpect(jsonPath("$.runningDynamics.cadenceSpm.comparable").value(false))
    }

    // ---- limitations, which are the point ----------------------------------------------------------

    @Test
    fun `activities with no stored analysis are counted as unanalysed, not silently analysed`() {
        run(0, 10000.0, 3000)
        run(1, 10000.0, 3000)

        report()
            .andExpect(jsonPath("$.coverage.analysedActivityCount").value(0))
            .andExpect(jsonPath("$.limitations.unanalysedActivityCount").value(2))

        // The read must not have created any analysis rows.
        assertThat(jdbc.queryForObject("select count(*) from activity_analysis", Int::class.java)).isZero()
    }

    @Test
    fun `an imported history reports what those sources could never carry`() {
        run(0, 10000.0, 3000, source = ExternalSource.INTERVALS_ICU)

        report()
            .andExpect(jsonPath("$.limitations.metricUnavailability[?(@.metric == 'CADENCE')].affectedActivityCount")
                .value(1))
            .andExpect(jsonPath("$.limitations.metricUnavailability[?(@.metric == 'CADENCE')].reason").isNotEmpty)
    }

    @Test
    fun `an all-Garmin history reports no structural gaps`() {
        run(0, 10000.0, 3000)

        report().andExpect(jsonPath("$.limitations.metricUnavailability").isEmpty)
    }

    /**
     * The invariant of this whole package: it measures, it never judges. A report an athlete reads
     * about themselves is exactly where a stray rating would do the most harm.
     */
    @Test
    fun `the report contains no rating, score or verdict anywhere`() {
        run(0, 10000.0, 3000, source = ExternalSource.INTERVALS_ICU)
        run(20, 21097.0, 7200)

        val body = report().andExpect(status().isOk).andReturn().response.contentAsString.lowercase()

        listOf("\"score\"", "\"rating\"", "\"grade\"", "\"readiness\"", "\"risk\"",
            "excellent", "poor", "good", "bad", "improving", "declining", "should")
            .forEach { assertThat(body).withFailMessage("the report asserts a judgement: %s", it).doesNotContain(it) }
    }

    @Test
    fun `the report states its own limits rather than leaving them to be remembered`() {
        run(0, 10000.0, 3000)

        report()
            .andExpect(jsonPath("$.limitations.notes").isNotEmpty)
            .andExpect(jsonPath("$.limitations.notes[0]").isString)
    }
}
