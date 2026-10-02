package com.runningai.analysis

import com.runningai.activity.detail.SampleData
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Repeatability of identified interval groups, and the heart-rate fall across the recovery that follows
 * them (Phase 6H-4).
 *
 * Everything here is computed over **work repetitions only**. Warm-up, recovery and cool-down blocks are
 * never mixed into a consistency number: a cool-down lap would make an otherwise even set look ragged.
 *
 * `speedCvPercent` uses the **population** standard deviation (divisor n). The repetitions present are
 * the whole group, not a sample drawn from a larger one, so the sample correction (n-1) would be the
 * wrong estimator - and with two repetitions it would inflate the spread by about 41%.
 */
@Component
class IntervalMetricsCalculator(private val extractor: IntervalStructureExtractor) {

    /**
     * @param timeline sample -> seconds from the session epoch, as [SessionMetricsCalculator] builds it
     * @param sessionStart the instant that timeline's zero corresponds to; without it a lap cannot be
     *   placed on the sample timeline and only the lap-derived recovery duration is reported
     */
    fun enrich(
        groups: List<IntervalGroup>,
        blocks: List<WorkoutBlock>,
        timeline: List<Pair<SampleData, Double>>?,
        sessionStart: Instant?,
    ): List<IntervalGroup> = groups.map { group ->
        val speeds = group.repetitions.mapNotNull { it.averageSpeed?.takeIf { v -> v.isFinite() } }
        val firstSpeed = group.repetitions.first().averageSpeed
        val lastSpeed = group.repetitions.last().averageSpeed
        val firstHr = group.repetitions.first().averageHr
        val lastHr = group.repetitions.last().averageHr
        group.copy(
            meanSpeed = speeds.takeIf { it.isNotEmpty() }?.average(),
            speedStdDev = populationStdDev(speeds),
            speedCvPercent = coefficientOfVariationPercent(speeds),
            firstRepSpeed = firstSpeed,
            lastRepSpeed = lastSpeed,
            lastVsFirstSpeedChangePercent = changePercent(firstSpeed, lastSpeed),
            firstRepHr = firstHr,
            lastRepHr = lastHr,
            hrProgressionBpm = difference(firstHr, lastHr),
            recovery = recovery(group, blocks, timeline, sessionStart),
        )
    }

    /**
     * RunningAI interval recovery HR change - **not** Garmin's Recovery HR metric.
     *
     * Across the RECOVERY block that follows the group's last work repetition, heart rate at the start is
     * compared with heart rate at the end. Both ends are the mean of the samples inside a fixed
     * [RECOVERY_WINDOW_SECONDS]-second window (the first and last of the block), because a single sample
     * can swing by several beats and would make the number look more precise than it is. A block with no
     * usable heart rate, or one that cannot be placed on the sample timeline, reports what it can and
     * leaves the rest null.
     */
    private fun recovery(
        group: IntervalGroup,
        blocks: List<WorkoutBlock>,
        timeline: List<Pair<SampleData, Double>>?,
        sessionStart: Instant?,
    ): RecoveryHrChange {
        val block = extractor.recoveryBlockAfter(blocks, group) ?: return RecoveryHrChange()
        val lapDuration = block.durationSeconds
        if (timeline == null || sessionStart == null) return RecoveryHrChange(durationSeconds = lapDuration)

        val blockStart = block.startTime ?: return RecoveryHrChange(durationSeconds = lapDuration)
        val duration = lapDuration?.takeIf { it > 0.0 } ?: return RecoveryHrChange(durationSeconds = lapDuration)
        val start = (blockStart.toEpochMilli() - sessionStart.toEpochMilli()) / 1000.0
        val end = start + duration

        val inBlock = timeline.filter { it.second >= start && it.second <= end }
        if (inBlock.isEmpty()) return RecoveryHrChange(durationSeconds = lapDuration)

        val startHr = inBlock.filter { it.second <= start + RECOVERY_WINDOW_SECONDS }
            .averageOf { it.first.heartRate.validHr() }
            ?: inBlock.firstNotNullOfOrNull { it.first.heartRate.validHr() }
        val endHr = inBlock.filter { it.second >= end - RECOVERY_WINDOW_SECONDS }
            .averageOf { it.first.heartRate.validHr() }
            ?: inBlock.lastOrNull { it.first.heartRate.validHr() != null }?.first?.heartRate

        return RecoveryHrChange(
            startHr = startHr,
            endHr = endHr,
            // a fall is reported as a positive drop
            dropBpm = if (startHr == null || endHr == null) null else startHr - endHr,
            durationSeconds = lapDuration,
        )
    }

    private companion object {
        /** Fixed averaging window at each end of a recovery block. */
        const val RECOVERY_WINDOW_SECONDS = 10.0
    }
}
