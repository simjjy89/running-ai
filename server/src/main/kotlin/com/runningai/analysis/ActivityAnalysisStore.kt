package com.runningai.analysis

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Persistence for the derived analysis (Phase 6H-4). One row per activity; a re-analysis replaces the
 * derived rows and never touches `activity`, `activity_detail`, `activity_lap`, `activity_zone`,
 * `activity_sample` or any raw payload.
 */
@Component
class ActivityAnalysisStore(
    private val analyses: ActivityAnalysisRepository,
    private val groups: ActivityAnalysisIntervalGroupRepository,
    private val repetitions: ActivityAnalysisIntervalRepository,
) {

    @Transactional
    fun replace(analysis: ActivityAnalysis) {
        val row = analyses.findByActivityId(analysis.activityId)
            ?.also { it.apply(analysis) }
            ?: analyses.save(ActivityAnalysisEntity(analysis.activityId).apply { apply(analysis) })
        val analysisId = requireNotNull(analyses.saveAndFlush(row).id)

        // replace, never accumulate: a session that lost its interval structure must not keep the old one
        repetitions.deleteAllOfAnalysis(analysisId)
        groups.deleteAllOfAnalysis(analysisId)
        groups.saveAll(analysis.intervalGroups.map { ActivityAnalysisIntervalGroupEntity.of(analysisId, it) })
        repetitions.saveAll(
            analysis.intervalGroups.flatMap { g ->
                g.repetitions.map { ActivityAnalysisIntervalEntity.of(analysisId, g.groupIndex, it) }
            },
        )
    }

    @Transactional(readOnly = true)
    fun find(activityId: Long): ActivityAnalysis? {
        val row = analyses.findByActivityId(activityId) ?: return null
        val analysisId = requireNotNull(row.id)
        val repetitionsByGroup = repetitions
            .findByActivityAnalysisIdOrderByGroupIndexAscRepetitionIndexAsc(analysisId)
            .groupBy { it.groupIndex }
        val storedGroups = groups.findByActivityAnalysisIdOrderByGroupIndex(analysisId).map { g ->
            IntervalGroup(
                groupIndex = g.groupIndex,
                workoutStepIndex = g.workoutStepIndex,
                repetitions = repetitionsByGroup[g.groupIndex].orEmpty().map { it.toData() },
                meanSpeed = g.meanSpeed,
                speedStdDev = g.speedStdDev,
                speedCvPercent = g.speedCvPercent,
                firstRepSpeed = g.firstRepSpeed,
                lastRepSpeed = g.lastRepSpeed,
                lastVsFirstSpeedChangePercent = g.lastVsFirstSpeedChangePercent,
                firstRepHr = g.firstRepHr,
                lastRepHr = g.lastRepHr,
                hrProgressionBpm = g.hrProgressionBpm,
                recovery = RecoveryHrChange(
                    g.recoveryStartHr, g.recoveryEndHr, g.recoveryHrDropBpm, g.recoveryDurationSeconds,
                ),
            )
        }
        return row.toData(storedGroups)
    }
}
