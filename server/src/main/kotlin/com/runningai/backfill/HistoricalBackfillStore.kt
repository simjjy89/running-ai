package com.runningai.backfill

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate

/**
 * Persistence of the backfill run and its per-activity checkpoints. Every mutation commits in its own
 * transaction ([Propagation.REQUIRES_NEW]) so a checkpoint survives whatever happens to the orchestrator
 * afterwards - that is the whole point of a checkpoint. The orchestrator itself is never transactional
 * (it makes network calls).
 */
@Service
class HistoricalBackfillStore(
    private val runs: HistoricalBackfillRunRepository,
    private val items: HistoricalBackfillActivityRepository,
) {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun createRun(athleteId: Long, startDate: LocalDate, endDate: LocalDate, days: Int, startedAt: Instant): HistoricalBackfillRunEntity =
        runs.save(HistoricalBackfillRunEntity(athleteId, startDate, endDate, days, startedAt))

    @Transactional(readOnly = true)
    fun run(runId: Long): HistoricalBackfillRunEntity? = runs.findById(runId).orElse(null)

    @Transactional(readOnly = true)
    fun hasRunningRun(athleteId: Long): Boolean = runs.existsByAthleteIdAndStatus(athleteId, BackfillRunStatus.RUNNING)

    /** Applies [mutation] to the run and commits immediately. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun updateRun(runId: Long, mutation: (HistoricalBackfillRunEntity) -> Unit): HistoricalBackfillRunEntity {
        val run = runs.findById(runId).orElseThrow { IllegalStateException("backfill run $runId disappeared") }
        mutation(run)
        return runs.save(run)
    }

    /** Inserts or refreshes one discovered activity (idempotent across discovery restarts). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun upsertDiscoveredItem(
        runId: Long,
        garminExternalId: String,
        startedAt: Instant,
        activityId: Long?,
        summaryStatus: BackfillSummaryStatus,
    ): HistoricalBackfillActivityEntity {
        val existing = items.findByRunIdAndGarminExternalId(runId, garminExternalId)
        val row = if (existing != null) {
            existing.startedAt = startedAt
            existing.activityId = activityId
            // A re-discovered, already-ingested activity reports UPDATED; the first pass' CREATED is the
            // interesting fact for the report, so it is kept.
            if (existing.summaryStatus != BackfillSummaryStatus.CREATED || summaryStatus == BackfillSummaryStatus.SKIPPED_UNSUPPORTED) {
                existing.summaryStatus = summaryStatus
            }
            existing
        } else {
            items.save(HistoricalBackfillActivityEntity(runId, garminExternalId, startedAt, activityId, summaryStatus))
        }
        items.flush()
        return row
    }

    /** Freezes the processing order: ordinal 1..n by started_at ASC (ties by external id). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun freezeOrdinals(runId: Long): Int {
        val all = items.findByRunIdOrderByStartedAtAscGarminExternalIdAsc(runId)
        all.forEachIndexed { index, item -> item.ordinal = index + 1 }
        items.saveAll(all)
        return all.count { it.summaryStatus != BackfillSummaryStatus.SKIPPED_UNSUPPORTED }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun updateItem(itemId: Long, mutation: (HistoricalBackfillActivityEntity) -> Unit): HistoricalBackfillActivityEntity {
        val item = items.findById(itemId).orElseThrow { IllegalStateException("backfill item $itemId disappeared") }
        mutation(item)
        return items.save(item)
    }

    @Transactional(readOnly = true)
    fun itemsOf(runId: Long): List<HistoricalBackfillActivityEntity> =
        items.findByRunIdOrderByStartedAtAscGarminExternalIdAsc(runId)
}
