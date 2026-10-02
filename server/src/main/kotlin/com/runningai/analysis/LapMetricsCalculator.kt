package com.runningai.analysis

import com.runningai.activity.detail.LapData
import org.springframework.stereotype.Component

/**
 * Laps exactly as the source recorded them, across the whole activity (Phase 6H-4).
 *
 * These numbers describe every lap, warm-up and cool-down included, so they are **not** a measure of
 * interval consistency - a session that opens with a slow warm-up lap will show a wide spread here and
 * still have perfectly even repetitions. Interval repeatability is computed separately, over work
 * repetitions only ([IntervalMetricsCalculator]).
 *
 * `speedCvPercent` uses the population standard deviation, like every other spread in this package.
 */
@Component
class LapMetricsCalculator {

    fun calculate(laps: List<LapData>): LapMetrics {
        if (laps.isEmpty()) return LapMetrics()
        val ordered = laps.sortedBy { it.lapIndex }
        val speeds = ordered.mapNotNull { it.averageSpeed?.takeIf { v -> v.isFinite() } }
        val heartRates = ordered.mapNotNull { it.averageHeartRateBpm.validHr() }
        return LapMetrics(
            lapCount = ordered.size,
            speedMean = speeds.takeIf { it.isNotEmpty() }?.average(),
            speedStdDev = populationStdDev(speeds),
            speedCvPercent = coefficientOfVariationPercent(speeds),
            hrMean = heartRates.takeIf { it.isNotEmpty() }?.average(),
            // last reporting lap minus first reporting lap: the direction heart rate moved across the
            // session's laps, not a rate and not a judgement
            hrProgression = if (heartRates.size >= 2) heartRates.last() - heartRates.first() else null,
            cadenceMean = ordered.mapNotNull { it.averageCadence.validNonNegative() }
                .takeIf { it.isNotEmpty() }?.average(),
        )
    }
}
