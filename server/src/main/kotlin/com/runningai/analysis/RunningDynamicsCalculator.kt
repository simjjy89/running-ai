package com.runningai.analysis

import com.fasterxml.jackson.databind.JsonNode
import com.runningai.activity.detail.LapData
import org.springframework.stereotype.Component
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Garmin running-dynamics evidence, per lap (Phase 7B-3).
 *
 * Source: each lap's own `extra_metrics` (the Garmin SPLITS payload carries `strideLength`,
 * `groundContactTime`, `verticalOscillation`, `verticalRatio` and `avgGradeAdjustedSpeed` per lap,
 * confirmed live on a real FR265 half-marathon - these are **not** reconstructed from the sample
 * stream, they are Garmin's own per-lap averages). Units are read as reported, never estimated:
 * centimeters (stride length, vertical oscillation), a dimensionless percent (vertical ratio),
 * milliseconds (ground contact time) - cross-checked against the independently descriptor-confirmed
 * sample-stream units for the same activity and found to agree to within rounding.
 *
 * Running power uses the existing first-class `LapData.averagePower`/`maxPower` (Garmin lap summary
 * fields, Phase 6H-4), not a value out of `extra_metrics`.
 */
data class RunningDynamicsMetrics(
    val lapsWithDynamicsCount: Int = 0,
    val avgStrideLengthCm: Double? = null,
    val firstHalfAvgStrideLengthCm: Double? = null,
    val secondHalfAvgStrideLengthCm: Double? = null,
    val strideLengthChangePercent: Double? = null,
    val samePaceStrideLengthChangeCm: Double? = null,
    val avgVerticalOscillationCm: Double? = null,
    val firstHalfAvgVerticalOscillationCm: Double? = null,
    val secondHalfAvgVerticalOscillationCm: Double? = null,
    val verticalOscillationChangePercent: Double? = null,
    val avgVerticalRatioPercent: Double? = null,
    val firstHalfAvgVerticalRatioPercent: Double? = null,
    val secondHalfAvgVerticalRatioPercent: Double? = null,
    val verticalRatioChangePercent: Double? = null,
    val avgGroundContactTimeMs: Double? = null,
    val firstHalfAvgGroundContactTimeMs: Double? = null,
    val secondHalfAvgGroundContactTimeMs: Double? = null,
    val groundContactTimeChangePercent: Double? = null,
    val avgRunningPowerWatts: Double? = null,
    val maxRunningPowerWatts: Double? = null,
    val firstHalfAvgRunningPowerWatts: Double? = null,
    val secondHalfAvgRunningPowerWatts: Double? = null,
    val runningPowerChangePercent: Double? = null,
    val samePaceRunningPowerChangeWatts: Double? = null,
)

/**
 * Computes [RunningDynamicsMetrics] from a run's laps. Never invoked for a non-running activity type
 * by [com.runningai.analysis.RunningActivityAnalysisService] (running dynamics is a running
 * measurement, like cadence); a run with no dynamics in any lap also returns every field `null`.
 *
 * **Halves here are split by lap order (first half of laps vs second half), not by elapsed time** -
 * unlike [SessionMetricsCalculator]'s sample-level halves. This is a deliberate, disclosed
 * simplification: lap-level Garmin fields have no per-lap elapsed-time range to split further, and
 * laps already approximate even time slices (nominal 1&nbsp;km splits). It is never mixed with or
 * presented as the same "halves" as the session-level speed/HR/cadence split.
 *
 * No rating, no good/bad classification, no diagnosis of an injury cause: this class only measures
 * what Garmin reported.
 */
@Component
class RunningDynamicsCalculator {

    fun calculate(laps: List<LapData>, isRun: Boolean): RunningDynamicsMetrics {
        if (!isRun || laps.isEmpty()) return RunningDynamicsMetrics()
        val ordered = laps.sortedBy { it.lapIndex }

        val stride = perLap(ordered) { it.extra("strideLength") }
        val gct = perLap(ordered) { it.extra("groundContactTime") }
        val vo = perLap(ordered) { it.extra("verticalOscillation") }
        val vr = perLap(ordered) { it.extra("verticalRatio") }
        val power = perLap(ordered) { it.averagePower.validNonNegative() }
        val pace = ordered.map { paceSecondsPerKm(it.averageSpeed) }

        val strideCount = stride.count { it != null }
        val gctCount = gct.count { it != null }
        val voCount = vo.count { it != null }
        val vrCount = vr.count { it != null }
        val powerCount = power.count { it != null }
        val lapsWithAny = ordered.indices.count {
            stride[it] != null || gct[it] != null || vo[it] != null || vr[it] != null || power[it] != null
        }

        val strideHalves = halves(stride)
        val gctHalves = halves(gct)
        val voHalves = halves(vo)
        val vrHalves = halves(vr)
        val powerHalves = halves(power)

        return RunningDynamicsMetrics(
            lapsWithDynamicsCount = lapsWithAny,
            avgStrideLengthCm = average(stride, strideCount),
            firstHalfAvgStrideLengthCm = strideHalves.first,
            secondHalfAvgStrideLengthCm = strideHalves.second,
            strideLengthChangePercent = changePercent(strideHalves.first, strideHalves.second),
            samePaceStrideLengthChangeCm = samePaceChange(pace, stride),
            avgVerticalOscillationCm = average(vo, voCount),
            firstHalfAvgVerticalOscillationCm = voHalves.first,
            secondHalfAvgVerticalOscillationCm = voHalves.second,
            verticalOscillationChangePercent = changePercent(voHalves.first, voHalves.second),
            avgVerticalRatioPercent = average(vr, vrCount),
            firstHalfAvgVerticalRatioPercent = vrHalves.first,
            secondHalfAvgVerticalRatioPercent = vrHalves.second,
            verticalRatioChangePercent = changePercent(vrHalves.first, vrHalves.second),
            avgGroundContactTimeMs = average(gct, gctCount),
            firstHalfAvgGroundContactTimeMs = gctHalves.first,
            secondHalfAvgGroundContactTimeMs = gctHalves.second,
            groundContactTimeChangePercent = changePercent(gctHalves.first, gctHalves.second),
            avgRunningPowerWatts = average(power, powerCount),
            maxRunningPowerWatts = ordered.mapNotNull { it.maxPower.validNonNegative() }.takeIf { it.isNotEmpty() }?.max(),
            firstHalfAvgRunningPowerWatts = powerHalves.first,
            secondHalfAvgRunningPowerWatts = powerHalves.second,
            runningPowerChangePercent = changePercent(powerHalves.first, powerHalves.second),
            samePaceRunningPowerChangeWatts = samePaceChange(pace, power),
        )
    }

    private fun LapData.extra(key: String): Double? {
        val node: JsonNode = extraMetrics?.get(key) ?: return null
        if (!node.isNumber) return null
        val v = node.asDouble()
        return v.takeIf { it.isFinite() }
    }

    private fun perLap(laps: List<LapData>, selector: (LapData) -> Double?): List<Double?> = laps.map(selector)

    private fun average(values: List<Double?>, count: Int): Double? {
        if (count == 0) return null
        return values.filterNotNull().average()
    }

    /** Mean of the first half of laps (by lap order) vs the second half; null when fewer than 2 laps report it. */
    private fun halves(values: List<Double?>): Pair<Double?, Double?> {
        val present = values.withIndex().filter { it.value != null }
        if (present.size < 2) return null to null
        val mid = present.size / 2
        val first = present.take(mid).mapNotNull { it.value }
        val second = present.drop(mid).mapNotNull { it.value }
        if (first.isEmpty() || second.isEmpty()) return null to null
        return first.average() to second.average()
    }

    private fun paceSecondsPerKm(speed: Double?): Double? {
        val s = speed?.validNonNegative()?.takeIf { it > 0.0 } ?: return null
        return 1000.0 / s
    }

    /**
     * Compares [values] between the two laps that (a) share the most similar pace (rounded to the
     * nearest [PACE_BUCKET_SECONDS] seconds/km) and (b) are at least [MIN_LAP_SEPARATION] laps apart -
     * i.e. a genuine "same effort, different point in the session" comparison, not two adjacent laps
     * that are trivially alike. Returns the later lap's value minus the earlier lap's. `null` when no
     * such pair exists (most sessions, including a steady-state race, legitimately have none).
     */
    private fun samePaceChange(pace: List<Double?>, values: List<Double?>): Double? {
        val candidates = pace.indices.mapNotNull { i ->
            val p = pace[i] ?: return@mapNotNull null
            val v = values[i] ?: return@mapNotNull null
            Triple(i, (p / PACE_BUCKET_SECONDS).roundToInt(), v)
        }
        var best: Pair<Int, Double>? = null // lap-index span, value difference
        for (a in candidates.indices) {
            for (b in a + 1 until candidates.size) {
                val (ia, bucketA, va) = candidates[a]
                val (ib, bucketB, vb) = candidates[b]
                if (bucketA != bucketB) continue
                val span = abs(ib - ia)
                if (span < MIN_LAP_SEPARATION) continue
                if (best == null || span > best!!.first) {
                    best = span to (vb - va)
                }
            }
        }
        return best?.second
    }

    private companion object {
        const val PACE_BUCKET_SECONDS = 15.0
        const val MIN_LAP_SEPARATION = 3
    }
}
