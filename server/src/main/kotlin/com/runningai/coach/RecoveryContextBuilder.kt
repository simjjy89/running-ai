package com.runningai.coach

import com.runningai.recovery.RecoveryBaselineService
import com.runningai.recovery.RecoveryBaselines
import com.runningai.recovery.RecoveryMetric
import com.runningai.recovery.RecoveryMetricBaseline
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * Turns the stored Garmin recovery snapshots into the coach's [RecoveryContext].
 *
 * All arithmetic (latest value, 28-day personal baseline, difference, percentage, sample count) is
 * done by [RecoveryBaselineService]; this class only re-shapes it and converts sleep seconds to
 * hours. It adds no rating and no recommendation, and a metric without a reading stays `null`.
 */
@Service
@Transactional(readOnly = true)
class RecoveryContextBuilder(
    private val baselineService: RecoveryBaselineService,
) {

    fun build(date: LocalDate): RecoveryContext = from(baselineService.baselines(date))

    companion object {

        /** Pure mapping, exposed for tests. */
        fun from(baselines: RecoveryBaselines): RecoveryContext {
            fun metric(m: RecoveryMetric): RecoveryMetricBaseline? = baselines.metric(m).orElse(null)

            val hrv = metric(RecoveryMetric.HRV_LAST_NIGHT_AVG_MS)?.let {
                val day = baselines.dayOf(it)
                HrvRecovery(measurement(it), day.hrvWeeklyAvgMs(), day.hrvStatus())
            }
            val sleepDuration = metric(RecoveryMetric.SLEEP_DURATION_SECONDS)?.let { measurement(it, SECONDS_PER_HOUR) }
            val sleepScore = metric(RecoveryMetric.SLEEP_SCORE)?.let { measurement(it) }
            val sleep = if (sleepDuration != null || sleepScore != null) SleepRecovery(sleepDuration, sleepScore) else null
            val restingHeartRate = metric(RecoveryMetric.RESTING_HEART_RATE_BPM)?.let {
                RestingHeartRateRecovery(measurement(it))
            }
            val bodyBattery = metric(RecoveryMetric.BODY_BATTERY_HIGHEST)?.let {
                val day = baselines.dayOf(it)
                BodyBatteryRecovery(measurement(it), day.bodyBatteryLowest(), day.bodyBatteryCharged(), day.bodyBatteryDrained())
            }
            val stress = metric(RecoveryMetric.STRESS_AVERAGE)?.let {
                StressRecovery(measurement(it), baselines.dayOf(it).stressMax())
            }
            return RecoveryContext(hrv, sleep, restingHeartRate, bodyBattery, stress)
        }

        private const val SECONDS_PER_HOUR = 3600.0

        /** [divisor] converts units (sleep seconds -> hours); the percentage is unit-free and unchanged. */
        private fun measurement(b: RecoveryMetricBaseline, divisor: Double = 1.0): RecoveryMeasurement {
            val scale = if (divisor == 1.0) 1 else 2
            fun convert(v: Double?): Double? = v?.let { round(it / divisor, scale) }
            return RecoveryMeasurement(
                date = b.date(),
                ageDays = b.ageDays(),
                current = round(b.current() / divisor, scale),
                baseline = convert(b.baseline()),
                difference = convert(b.difference()),
                differencePercent = b.differencePercent(),
                sampleCount = b.sampleCount(),
                baselineWindowDays = b.windowDays(),
                minimumSamples = b.minimumSamples(),
                baselineStatus = b.status(),
            )
        }

        private fun round(value: Double, scale: Int): Double =
            BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).toDouble()
    }
}
