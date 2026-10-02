package com.runningai.analysis

import com.runningai.activity.detail.SampleData
import com.runningai.activity.detail.ZoneData
import org.springframework.stereotype.Component

/**
 * Time in heart-rate zone, straight from what the source reported (Phase 6H-4).
 *
 * Percentages are of the **sum of the reported zone durations**, not of the activity duration. Live
 * Garmin zone time and activity duration are close but not identical, and only the former is something
 * the source actually vouches for; using the activity duration as the denominator would silently invent
 * a "no zone" remainder. Zones the source did not report are simply absent - never filled with zero.
 */
@Component
class ZoneExposureCalculator {

    fun calculate(zones: List<ZoneData>): ZoneExposure {
        val seconds = zones
            .mapNotNull { z -> z.durationSeconds?.takeIf { it.isFinite() && it >= 0.0 }?.let { z.zoneNumber to it } }
            .toMap()
        if (seconds.isEmpty()) return ZoneExposure()
        val total = seconds.values.sum()
        val percent = if (total > 0.0) seconds.mapValues { (_, s) -> s / total * 100.0 } else emptyMap()
        return ZoneExposure(seconds, total, percent)
    }
}

/**
 * Seconds spent at or above a share of the athlete's lactate-threshold heart rate (Phase 6H-4).
 *
 * Time is **integrated over the real gaps between samples**, never counted as one second per sample: a
 * down-sampled Garmin stream has uneven gaps, so counting samples would understate a long session by
 * roughly its down-sampling factor. Each sample is credited with the gap to the next one, which means the
 * final sample contributes nothing - there is no evidence about how long the last reading held, and
 * inventing a trailing second would be a guess.
 *
 * Without an LTHR on file every value is null. Nothing is substituted for it.
 */
@Component
class ThresholdExposureCalculator(private val sessionMetrics: SessionMetricsCalculator) {

    fun calculate(samples: List<SampleData>, lactateThresholdHr: Int?): ThresholdExposure {
        if (lactateThresholdHr == null || lactateThresholdHr <= 0) return ThresholdExposure()
        val timeline = sessionMetrics.timeline(samples) ?: return ThresholdExposure()
        val lthr = lactateThresholdHr.toDouble()
        return ThresholdExposure(
            lthr90Seconds = secondsAtOrAbove(timeline, lthr * 0.90),
            lthr95Seconds = secondsAtOrAbove(timeline, lthr * 0.95),
            lthr100Seconds = secondsAtOrAbove(timeline, lthr),
        )
    }

    private fun secondsAtOrAbove(timeline: List<Pair<SampleData, Double>>, bpm: Double): Double {
        var seconds = 0.0
        for (i in 0 until timeline.size - 1) {
            val hr = timeline[i].first.heartRate.validHr() ?: continue
            if (hr >= bpm) seconds += timeline[i + 1].second - timeline[i].second
        }
        return seconds
    }
}
