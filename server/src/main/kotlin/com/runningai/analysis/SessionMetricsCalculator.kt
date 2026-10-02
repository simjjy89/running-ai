package com.runningai.analysis

import com.runningai.activity.detail.SampleData
import org.springframework.stereotype.Component

/**
 * Session-level evidence from a sample stream (Phase 6H-4). Pure: no Spring state, no I/O, no clock.
 *
 * **Valid-sample policy.** A metric uses only the samples that actually report it: heart rate must be
 * present and above zero (zero means "not measured", not "the heart stopped"); speed and cadence must be
 * present and not negative, and a zero is kept because standing still is part of the session. There is no
 * moving-time filter and no speed threshold: inventing one would quietly change every average, and
 * nothing in the stored data says where a pause began.
 *
 * **Halves** are split at the midpoint of *elapsed time*, never at the midpoint of the sample array -
 * Garmin's stream is not uniformly spaced (a live default-resolution run had 1 s and 2 s gaps in the same
 * activity), so an index split would weight the halves differently. When elapsed seconds are unusable the
 * sample timestamps are used instead; when neither is usable the halves stay null rather than falling back
 * to an index split.
 */
@Component
class SessionMetricsCalculator {

    fun calculate(samples: List<SampleData>, includeCadence: Boolean): SessionMetrics {
        if (samples.isEmpty()) return SessionMetrics(validSampleCount = 0)

        val timeline = timeline(samples)
        val validSampleCount = samples.count { it.heartRate.validHr() != null || it.speed.validNonNegative() != null }
        val duration = timeline?.let { it.last().second - it.first().second }?.takeIf { it > 0.0 }
        val distance = distanceCovered(samples)

        val halves = timeline?.let { halves(it, includeCadence) } ?: HalfSplitMetrics()
        return SessionMetrics(validSampleCount, duration, distance, halves)
    }

    /**
     * (sample, seconds-from-start) in source order, or null when no usable time base exists.
     * Elapsed seconds are preferred; sample timestamps are the fallback. A time base that never advances
     * is not a time base.
     */
    internal fun timeline(samples: List<SampleData>): List<Pair<SampleData, Double>>? {
        if (samples.size < 2) return null
        val byElapsed = samples.mapNotNull { s -> s.elapsedSeconds?.takeIf { it.isFinite() }?.let { s to it } }
        if (byElapsed.size == samples.size && byElapsed.isNonDecreasing() && byElapsed.spansTime()) {
            val zero = byElapsed.first().second
            return byElapsed.map { (s, t) -> s to (t - zero) }
        }
        val byTimestamp = samples.mapNotNull { s -> s.sampleTime?.let { s to it } }
        if (byTimestamp.size == samples.size) {
            val start = byTimestamp.first().second
            val seconds = byTimestamp.map { (s, t) -> s to (t.toEpochMilli() - start.toEpochMilli()) / 1000.0 }
            if (seconds.isNonDecreasing() && seconds.spansTime()) return seconds
        }
        return null
    }

    private fun List<Pair<SampleData, Double>>.isNonDecreasing() = zipWithNext().all { (a, b) -> b.second >= a.second }

    private fun List<Pair<SampleData, Double>>.spansTime() = size >= 2 && last().second > first().second

    private fun distanceCovered(samples: List<SampleData>): Double? {
        val distances = samples.mapNotNull { it.distance?.takeIf { d -> d.isFinite() && d >= 0 } }
        if (distances.size < 2) return null
        val covered = distances.last() - distances.first()
        return covered.takeIf { it >= 0 }
    }

    private fun halves(timeline: List<Pair<SampleData, Double>>, includeCadence: Boolean): HalfSplitMetrics {
        val midpoint = (timeline.first().second + timeline.last().second) / 2.0
        val first = timeline.filter { it.second < midpoint }.map { it.first }
        val second = timeline.filter { it.second >= midpoint }.map { it.first }
        if (first.isEmpty() || second.isEmpty()) return HalfSplitMetrics()

        val hr1 = first.averageOf { it.heartRate.validHr() }
        val hr2 = second.averageOf { it.heartRate.validHr() }
        val sp1 = first.averageOf { it.speed.validNonNegative() }
        val sp2 = second.averageOf { it.speed.validNonNegative() }
        val cad1 = if (includeCadence) first.averageOf { it.cadence.validNonNegative() } else null
        val cad2 = if (includeCadence) second.averageOf { it.cadence.validNonNegative() } else null

        val ef1 = ratio(sp1, hr1)
        val ef2 = ratio(sp2, hr2)

        return HalfSplitMetrics(
            firstHalfAvgHr = hr1,
            secondHalfAvgHr = hr2,
            hrChangeBpm = difference(hr1, hr2),
            hrChangePercent = changePercent(hr1, hr2),
            firstHalfAvgSpeed = sp1,
            secondHalfAvgSpeed = sp2,
            speedChangePercent = changePercent(sp1, sp2),
            firstHalfAvgCadence = cad1,
            secondHalfAvgCadence = cad2,
            cadenceChangeSpm = difference(cad1, cad2),
            cadenceChangePercent = changePercent(cad1, cad2),
            firstHalfSpeedHrRatio = ef1,
            secondHalfSpeedHrRatio = ef2,
            // (EF1 - EF2) / EF1 * 100, i.e. speed per heartbeat in the first half against the second:
            // positive = the second half bought less speed per beat. A RunningAI-derived descriptive
            // number, not a Garmin or Intervals metric, and it carries no threshold.
            speedHrDecouplingPercent = decouplingPercent(ef1, ef2),
        )
    }

    private fun ratio(speed: Double?, hr: Double?): Double? {
        if (speed == null || hr == null || hr == 0.0) return null
        return (speed / hr).takeIf { it.isFinite() }
    }
}

internal fun Double?.validHr(): Double? = this?.takeIf { it.isFinite() && it > 0.0 }

internal fun Double?.validNonNegative(): Double? = this?.takeIf { it.isFinite() && it >= 0.0 }

internal fun <T> List<T>.averageOf(selector: (T) -> Double?): Double? {
    val values = mapNotNull(selector)
    return if (values.isEmpty()) null else values.average()
}

internal fun difference(from: Double?, to: Double?): Double? =
    if (from == null || to == null) null else to - from

/** `(to - from) / from * 100`. Null when either side is missing or the baseline is zero. */
internal fun changePercent(from: Double?, to: Double?): Double? {
    if (from == null || to == null || from == 0.0) return null
    return ((to - from) / from * 100.0).takeIf { it.isFinite() }
}

/**
 * `(EF1 - EF2) / EF1 * 100`, where EF is average speed divided by average heart rate for that half.
 * Null when either ratio is missing or EF1 is zero.
 */
internal fun decouplingPercent(ef1: Double?, ef2: Double?): Double? {
    if (ef1 == null || ef2 == null || ef1 == 0.0) return null
    return ((ef1 - ef2) / ef1 * 100.0).takeIf { it.isFinite() }
}

/** Population standard deviation (divisor n): the values present are the whole set, not a sample of it. */
internal fun populationStdDev(values: List<Double>): Double? {
    if (values.isEmpty()) return null
    val mean = values.average()
    return kotlin.math.sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
}

/** Population standard deviation as a percentage of the mean. Null when the mean is zero. */
internal fun coefficientOfVariationPercent(values: List<Double>): Double? {
    if (values.isEmpty()) return null
    val mean = values.average()
    if (mean == 0.0) return null
    val sd = populationStdDev(values) ?: return null
    return (sd / mean * 100.0).takeIf { it.isFinite() }
}
