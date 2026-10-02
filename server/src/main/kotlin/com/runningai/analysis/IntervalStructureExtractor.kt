package com.runningai.analysis

import com.runningai.activity.detail.LapData
import org.springframework.stereotype.Component

/**
 * Turns laps into the workout structure the device recorded (Phase 6H-4).
 *
 * Phase 6H-1B established live that **a Garmin lap is not a workout step**: on a real interval run one
 * step spanned nine laps, two steps alternated as a repeat block, one step index never appeared, and the
 * final lap carried no step at all. So a *block* is a run of consecutive laps sharing the same
 * `intensityType` + `workoutStepIndex`, and the same step index reappearing later is a separate block -
 * that recurrence is exactly how a repeat is expressed.
 *
 * Structure is read, never inferred. Nothing here looks at speed or heart rate to guess where an interval
 * was: an unstructured run simply yields no groups.
 */
@Component
class IntervalStructureExtractor {

    /** Consecutive laps with the same (intensityType, workoutStepIndex) collapsed into one block. */
    fun blocks(laps: List<LapData>): List<WorkoutBlock> {
        if (laps.isEmpty()) return emptyList()
        val ordered = laps.sortedBy { it.lapIndex }
        val blocks = mutableListOf<WorkoutBlock>()
        var run = mutableListOf(ordered.first())
        for (lap in ordered.drop(1)) {
            val previous = run.last()
            if (lap.intensityType == previous.intensityType && lap.workoutStepIndex == previous.workoutStepIndex) {
                run += lap
            } else {
                blocks += block(run)
                run = mutableListOf(lap)
            }
        }
        blocks += block(run)
        return blocks
    }

    /**
     * Work repetitions grouped by step index.
     *
     * A group is only identified when the device's own structure says so: the blocks are ACTIVE, share a
     * `workoutStepIndex`, occur at least twice, and at least one RECOVERY block sits between two of those
     * occurrences. Anything short of that is "no interval structure identified" rather than a guess -
     * back-to-back ACTIVE blocks with no recovery between them are not repetitions of an interval.
     */
    fun groups(blocks: List<WorkoutBlock>): List<IntervalGroup> {
        val active = blocks.withIndex().filter { (_, b) -> b.intensityType.isIntensity(ACTIVE) && b.workoutStepIndex != null }
        return active
            .groupBy { (_, b) -> b.workoutStepIndex!! }
            .filterValues { it.size >= 2 && hasRecoveryBetween(blocks, it.map { (i, _) -> i }) }
            .toSortedMap(compareBy { it })
            .entries
            .sortedBy { (_, occurrences) -> occurrences.first().index }
            .mapIndexed { groupIndex, (stepIndex, occurrences) ->
                val repetitions = occurrences.mapIndexed { i, (_, b) ->
                    IntervalRepetition(
                        repetitionIndex = i + 1,
                        workoutStepIndex = stepIndex,
                        firstLapIndex = b.firstLapIndex,
                        lastLapIndex = b.lastLapIndex,
                        durationSeconds = b.durationSeconds,
                        distanceMeters = b.distanceMeters,
                        averageSpeed = b.averageSpeed,
                        averageHr = b.averageHr,
                        maxHr = b.maxHr,
                        averageCadence = b.averageCadence,
                        averagePower = b.averagePower,
                    )
                }
                IntervalGroup(groupIndex = groupIndex, workoutStepIndex = stepIndex, repetitions = repetitions)
            }
    }

    /** The RECOVERY block following a group's last work repetition, if the device recorded one. */
    fun recoveryBlockAfter(blocks: List<WorkoutBlock>, group: IntervalGroup): WorkoutBlock? {
        val lastLap = group.repetitions.lastOrNull()?.lastLapIndex ?: return null
        return blocks.firstOrNull { it.firstLapIndex > lastLap && it.intensityType.isIntensity(RECOVERY) }
    }

    private fun hasRecoveryBetween(blocks: List<WorkoutBlock>, occurrencePositions: List<Int>): Boolean =
        occurrencePositions.zipWithNext().any { (a, b) ->
            blocks.subList(a + 1, b).any { it.intensityType.isIntensity(RECOVERY) }
        }

    private fun block(laps: List<LapData>): WorkoutBlock {
        val duration = laps.sumOrNull { it.durationSeconds }
        return WorkoutBlock(
            intensityType = laps.first().intensityType,
            workoutStepIndex = laps.first().workoutStepIndex,
            firstLapIndex = laps.first().lapIndex,
            lastLapIndex = laps.last().lapIndex,
            startTime = laps.first().startTime,
            durationSeconds = duration,
            distanceMeters = laps.sumOrNull { it.distanceMeters },
            // a block of several laps is weighted by lap duration, so a 20 s lap does not count as much
            // as a 100 s one; without durations it falls back to the plain mean of what is reported
            averageSpeed = laps.weightedAverage({ it.averageSpeed }, { it.durationSeconds }),
            averageHr = laps.weightedAverage({ it.averageHeartRateBpm }, { it.durationSeconds }),
            maxHr = laps.mapNotNull { it.maxHeartRateBpm?.takeIf { v -> v.isFinite() } }.maxOrNull(),
            averageCadence = laps.weightedAverage({ it.averageCadence }, { it.durationSeconds }),
            averagePower = laps.weightedAverage({ it.averagePower }, { it.durationSeconds }),
        )
    }

    private companion object {
        const val ACTIVE = "ACTIVE"
        const val RECOVERY = "RECOVERY"
    }
}

/** Source vocabulary, compared case-insensitively; an unknown value simply matches nothing. */
internal fun String?.isIntensity(name: String): Boolean = this != null && this.equals(name, ignoreCase = true)

internal fun <T> List<T>.sumOrNull(selector: (T) -> Double?): Double? {
    val values = mapNotNull { selector(it)?.takeIf { v -> v.isFinite() } }
    return if (values.isEmpty()) null else values.sum()
}

internal fun <T> List<T>.weightedAverage(value: (T) -> Double?, weight: (T) -> Double?): Double? {
    val pairs = mapNotNull { item ->
        val v = value(item)?.takeIf { it.isFinite() } ?: return@mapNotNull null
        v to (weight(item)?.takeIf { it.isFinite() && it > 0.0 })
    }
    if (pairs.isEmpty()) return null
    val weights = pairs.mapNotNull { it.second }
    if (weights.size != pairs.size) return pairs.map { it.first }.average()
    val totalWeight = weights.sum()
    if (totalWeight <= 0.0) return pairs.map { it.first }.average()
    return pairs.sumOf { (v, w) -> v * w!! } / totalWeight
}
