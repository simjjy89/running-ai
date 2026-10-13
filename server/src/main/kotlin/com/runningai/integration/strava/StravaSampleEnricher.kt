package com.runningai.integration.strava

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.runningai.activity.detail.SampleData
import org.springframework.stereotype.Component
import java.time.Instant

/** The result of merging a FIT file into an activity's already-stored samples. */
data class SampleEnrichment(
    val samples: List<SampleData>,
    /** How many stored samples gained at least one value they did not have. */
    val samplesEnriched: Int,
    /** Which metrics were filled, and how many values each gained. */
    val filledByMetric: Map<String, Int>,
)

/**
 * Fills gaps in an activity's stored samples from the same workout's Strava FIT file (Phase 7E-2).
 *
 * Why this exists. The 25 activities imported in Phase 7E-1 and this archive are two INDEPENDENT
 * exports of the same Apple Health workout, and each is missing what the other has (measured over all
 * 25): Intervals.icu carries heart rate on 100% of samples while the August FITs carry none at all;
 * the FIT carries markedly better running power (2373 -> 6549 points on the 2026-08-18 long run) and
 * the laps Intervals reports as `icu_lap_count` = 0. Replacing either source with the other would
 * destroy real data, so neither is replaced.
 *
 * The rule is therefore absolute and one line long: **a stored value is never overwritten.** A metric
 * is filled only where the stored sample has null and the FIT has a value for the same instant, and
 * every filled metric stamps its own provenance key into `extra_metrics`, so a later reader can always
 * tell which source produced which number.
 *
 * Alignment is by whole second of the sample's absolute timestamp. A FIT record that matches no stored
 * sample is dropped rather than appended: inserting points into a stream that another source defined
 * would change what the sample index means and silently alter every index-based figure downstream.
 *
 * Pure: no I/O, no clock, no database.
 */
@Component
class StravaSampleEnricher(private val objectMapper: ObjectMapper) {

    fun enrich(stored: List<SampleData>, fit: FitActivity): SampleEnrichment {
        if (stored.isEmpty() || fit.records.isEmpty()) {
            return SampleEnrichment(stored, 0, emptyMap())
        }
        // Last writer wins for a duplicated second; FIT files record at most one point per second in
        // every file measured, so this only guards against a malformed one.
        val bySecond: Map<Long, FitRecord> = fit.records
            .mapNotNull { record -> record.timestamp?.let { it.epochSecond to record } }
            .toMap()

        var changed = 0
        val filled = mutableMapOf<String, Int>()
        val merged = stored.map { sample ->
            val match = sample.sampleTime?.let { bySecond[it.epochSecond] } ?: return@map sample
            var extras: ObjectNode? = sample.extraMetrics
            var touched = false

            fun <T : Any> fill(current: T?, candidate: T?, metric: String): T? {
                if (current != null || candidate == null) return current
                touched = true
                filled[metric] = (filled[metric] ?: 0) + 1
                if (extras == null) extras = objectMapper.createObjectNode()
                extras!!.put("${metric}_source", SOURCE)
                return candidate
            }

            val next = sample.copy(
                distance = fill(sample.distance, match.distance, "distance"),
                speed = fill(sample.speed, match.speed, "speed"),
                heartRate = fill(sample.heartRate, match.heartRateBpm, "heart_rate"),
                power = fill(sample.power, match.power, "power"),
                elevation = fill(sample.elevation, match.elevation, "elevation"),
                latitude = fill(sample.latitude, match.latitude, "latitude"),
                longitude = fill(sample.longitude, match.longitude, "longitude"),
                temperature = fill(sample.temperature, match.temperature, "temperature"),
                // cadence is deliberately absent: the FIT value is one-leg rpm and the column is spm.
                extraMetrics = extras,
            )
            if (touched) changed++
            next
        }
        return SampleEnrichment(merged, changed, filled.toSortedMap())
    }

    /**
     * Whether [fit] plausibly describes the same workout as the stored samples spanning
     * [storedStart]..[storedEnd]. A guard, not a matcher: identity has already been established by
     * [com.runningai.enrichment.StravaMatchMethod] before this is ever called, and this only refuses to
     * merge two streams whose clocks do not overlap at all.
     */
    fun overlaps(fit: FitActivity, storedStart: Instant?, storedEnd: Instant?): Boolean {
        if (storedStart == null || storedEnd == null) return false
        val first = fit.records.firstNotNullOfOrNull { it.timestamp } ?: return false
        val last = fit.records.lastOrNull { it.timestamp != null }?.timestamp ?: return false
        return !last.isBefore(storedStart) && !first.isAfter(storedEnd)
    }

    companion object {
        /** Provenance marker written next to every value this class contributes. */
        const val SOURCE: String = "STRAVA_FIT"
    }
}
