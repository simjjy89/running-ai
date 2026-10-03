package com.runningai.integration.intervals

import com.runningai.activity.Activity
import com.runningai.activity.ActivityType
import com.runningai.activity.ExternalSource
import com.runningai.enrichment.IntervalsActivitySnapshot
import com.runningai.enrichment.MatchMethod
import com.runningai.enrichment.MatchResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Matching rules and tolerances (from the 2026-10-03 live measurement: start delta 0 s on 5/5 pairs,
 * duration delta <= 1 s, distance delta <= 0.01 m). Pure, no Spring.
 */
class IntervalsActivityMatcherTest {

    private val matcher = IntervalsActivityMatcher()
    private val start: Instant = Instant.parse("2026-01-09T23:00:10Z")

    private fun garminActivity(
        type: ActivityType = ActivityType.RUN,
        startedAt: Instant = start,
        durationSeconds: Int = 2783,
        distanceMeters: Double? = 9917.21,
        externalId: String = "90000000001",
    ) = Activity(1L, ExternalSource.GARMIN, externalId, type, startedAt, durationSeconds, distanceMeters, 150, 180)

    private fun snapshot(
        id: String = "i00000001",
        source: String? = "GARMIN_CONNECT",
        externalId: String? = "90000000001",
        type: String? = "Run",
        startDate: Instant? = start,
        elapsed: Int? = 2783,
        distance: Double? = 9917.21,
    ) = IntervalsActivitySnapshot(id, source, externalId, type, startDate, elapsed, 2773, distance,
        89, 107.3, 14.98, 26.26, null)

    @Test
    fun `explicit source id wins - method SOURCE_ID with evidence`() {
        // Deliberately outside every composite tolerance: the explicit id must not need them.
        val candidate = snapshot(startDate = start.plusSeconds(600), elapsed = 9999, distance = 1.0, type = "Swim")
        val result = matcher.match(garminActivity(), listOf(candidate, snapshot(id = "i9", externalId = "other")))

        val matched = result as MatchResult.Matched
        assertThat(matched.method).isEqualTo(MatchMethod.SOURCE_ID)
        assertThat(matched.snapshot.intervalsActivityId).isEqualTo("i00000001")
        assertThat(matched.evidence.startTimeDeltaSeconds).isEqualTo(600)
        assertThat(matched.evidence.durationDeltaSeconds).isEqualTo(9999 - 2783)
        assertThat(matched.evidence.distanceDeltaMeters).isEqualTo(1.0 - 9917.21)
    }

    @Test
    fun `same external id without a Garmin source label is EXTERNAL_ID`() {
        val candidate = snapshot(source = "STRAVA")
        val result = matcher.match(garminActivity(), listOf(candidate))

        assertThat((result as MatchResult.Matched).method).isEqualTo(MatchMethod.EXTERNAL_ID)
    }

    @Test
    fun `two candidates claiming the same explicit id are ambiguous`() {
        val result = matcher.match(garminActivity(), listOf(snapshot(id = "i1"), snapshot(id = "i2")))

        assertThat((result as MatchResult.Ambiguous).candidateCount).isEqualTo(2)
    }

    @Test
    fun `composite match - exactly one candidate within every tolerance`() {
        val candidate = snapshot(id = "i7", source = null, externalId = null,
            startDate = start.plusSeconds(2), elapsed = 2784, distance = 9918.0)
        val result = matcher.match(garminActivity(), listOf(candidate, snapshot(id = "i8", source = null,
            externalId = null, startDate = start.plusSeconds(7200))))

        val matched = result as MatchResult.Matched
        assertThat(matched.method).isEqualTo(MatchMethod.COMPOSITE)
        assertThat(matched.snapshot.intervalsActivityId).isEqualTo("i7")
        assertThat(matched.evidence.startTimeDeltaSeconds).isEqualTo(2)
        assertThat(matched.evidence.durationDeltaSeconds).isEqualTo(1)
    }

    @Test
    fun `no candidate at all is UNMATCHED - a normal result`() {
        assertThat(matcher.match(garminActivity(), emptyList())).isEqualTo(MatchResult.Unmatched)
    }

    @Test
    fun `two composite candidates are AMBIGUOUS and never auto-linked`() {
        val a = snapshot(id = "i7", source = null, externalId = null)
        val b = snapshot(id = "i8", source = null, externalId = null)
        val result = matcher.match(garminActivity(), listOf(a, b))

        assertThat((result as MatchResult.Ambiguous).candidateCount).isEqualTo(2)
    }

    @Test
    fun `start time beyond 30 seconds does not composite-match`() {
        val candidate = snapshot(id = "i7", source = null, externalId = null, startDate = start.plusSeconds(31))
        assertThat(matcher.match(garminActivity(), listOf(candidate))).isEqualTo(MatchResult.Unmatched)
    }

    @Test
    fun `start times are compared as instants - timezone cannot shift a match`() {
        // 2026-01-10T08:00:10+09:00 is the same instant as 2026-01-09T23:00:10Z.
        val candidate = snapshot(id = "i7", source = null, externalId = null,
            startDate = java.time.OffsetDateTime.parse("2026-01-10T08:00:10+09:00").toInstant())
        val result = matcher.match(garminActivity(), listOf(candidate))

        assertThat((result as MatchResult.Matched).evidence.startTimeDeltaSeconds).isEqualTo(0)
    }

    @Test
    fun `duration beyond 5 seconds does not composite-match`() {
        val candidate = snapshot(id = "i7", source = null, externalId = null, elapsed = 2789)
        assertThat(matcher.match(garminActivity(), listOf(candidate))).isEqualTo(MatchResult.Unmatched)
    }

    @Test
    fun `distance beyond 5 metres does not composite-match`() {
        val candidate = snapshot(id = "i7", source = null, externalId = null, distance = 9923.0)
        assertThat(matcher.match(garminActivity(), listOf(candidate))).isEqualTo(MatchResult.Unmatched)
    }

    @Test
    fun `distance is skipped when one side has none - live indoor cycling is 0 m vs null`() {
        val activity = garminActivity(type = ActivityType.INDOOR_CYCLING, durationSeconds = 787, distanceMeters = 0.0)
        val candidate = snapshot(id = "i7", source = null, externalId = null, type = "VirtualRide",
            elapsed = 787, distance = null)
        val result = matcher.match(activity, listOf(candidate))

        assertThat((result as MatchResult.Matched).method).isEqualTo(MatchMethod.COMPOSITE)
        assertThat(result.evidence.distanceDeltaMeters).isNull()
    }

    @Test
    fun `an unobserved type combination never composite-matches`() {
        // TREADMILL_RUN was live-observed as VirtualRun, not Run: fail closed.
        val candidate = snapshot(id = "i7", source = null, externalId = null, type = "Run", elapsed = 1801, distance = 5393.08)
        val activity = garminActivity(type = ActivityType.TREADMILL_RUN, durationSeconds = 1801, distanceMeters = 5393.08)

        assertThat(matcher.match(activity, listOf(candidate))).isEqualTo(MatchResult.Unmatched)
    }

    @Test
    fun `a candidate explicitly claiming a different Garmin activity never composite-matches`() {
        // Metrically identical, but its own source identifier names another Garmin activity.
        val candidate = snapshot(id = "i7", externalId = "90000000777")
        assertThat(matcher.match(garminActivity(), listOf(candidate))).isEqualTo(MatchResult.Unmatched)
    }

    @Test
    fun `a candidate without start or elapsed cannot composite-match`() {
        val noStart = snapshot(id = "i7", source = null, externalId = null, startDate = null)
        val noElapsed = snapshot(id = "i8", source = null, externalId = null, elapsed = null)

        assertThat(matcher.match(garminActivity(), listOf(noStart, noElapsed))).isEqualTo(MatchResult.Unmatched)
    }
}
