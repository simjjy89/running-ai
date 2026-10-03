package com.runningai.backfill

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate

/** Deterministic Intervals windowing (§30) and the backfill property defaults. Pure, no Spring. */
class HistoricalBackfillWindowsTest {

    @Test
    fun `90 days split into three non-overlapping gap-free windows of at most 31 days`() {
        val windows = IntervalsHistoricalEnrichmentService.windows(
            LocalDate.parse("2026-07-06"), LocalDate.parse("2026-10-03"))

        assertThat(windows).containsExactly(
            LocalDate.parse("2026-07-06") to LocalDate.parse("2026-08-05"),
            LocalDate.parse("2026-08-06") to LocalDate.parse("2026-09-05"),
            LocalDate.parse("2026-09-06") to LocalDate.parse("2026-10-03"),
        )
        // no overlap, no gap, every day covered exactly once
        windows.zipWithNext().forEach { (a, b) ->
            assertThat(b.first).isEqualTo(a.second.plusDays(1))
        }
        windows.forEach { (from, to) ->
            assertThat(java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1)
                .isLessThanOrEqualTo(IntervalsHistoricalEnrichmentService.MAX_WINDOW_DAYS)
        }
    }

    @Test
    fun `a window shorter than the chunk size is one window`() {
        val windows = IntervalsHistoricalEnrichmentService.windows(
            LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-03"))

        assertThat(windows).containsExactly(LocalDate.parse("2026-10-01") to LocalDate.parse("2026-10-03"))
    }

    @Test
    fun `an inverted range is rejected`() {
        assertThatThrownBy {
            IntervalsHistoricalEnrichmentService.windows(LocalDate.parse("2026-10-03"), LocalDate.parse("2026-10-01"))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `property defaults - page size 100, caps present, 2s activity delay`() {
        val defaults = HistoricalBackfillProperties()

        assertThat(defaults.pageSize).isEqualTo(100)
        assertThat(defaults.maxPages).isEqualTo(20)
        assertThat(defaults.maxActivities).isEqualTo(500)
        assertThat(defaults.activityDelay).isEqualTo(Duration.ofSeconds(2))
    }
}
