package com.runningai.integration.intervals

import com.runningai.activity.Activity
import com.runningai.activity.ActivityType
import com.runningai.activity.ExternalSource
import com.runningai.enrichment.IntervalsActivitySnapshot
import com.runningai.enrichment.MatchEvidence
import com.runningai.enrichment.MatchMethod
import com.runningai.enrichment.MatchResult
import org.springframework.stereotype.Component
import kotlin.math.abs

/**
 * Matches one RunningAI (Garmin-sourced) activity against the Intervals.icu activities of its date
 * window. Priority order (Phase 6H-5 §14):
 *
 *  1. SOURCE_ID    — the Intervals payload names the upstream source AND carries the same Garmin
 *                    activity id (`source` = `GARMIN_CONNECT`, `external_id` = the Garmin id).
 *                    Live-verified on 5/5 activities of the real account (2026-10-03).
 *  2. EXTERNAL_ID  — `external_id` equals the Garmin id but `source` does not confirm Garmin.
 *  3. COMPOSITE    — no explicit id anywhere: type-compatible candidates within the tolerances below.
 *
 * Composite tolerances come from the live Garmin↔Intervals measurement of 2026-10-03 (5 pairs):
 * start-time delta was 0 s on every pair, duration delta at most 1 s, distance delta at most 0.01 m
 * (both sides read the same FIT file). The tolerances add only headroom for whole-second truncation
 * and metre-level rounding — they are NOT wide-net values:
 *
 *   start time  |Δ| <= 30 s
 *   duration    |Δ| <= 5 s     (RunningAI duration_seconds vs Intervals elapsed_time)
 *   distance    |Δ| <= 5 m     (skipped when either side has no distance; live: Garmin indoor
 *                               cycling stores 0 m where Intervals has null)
 *
 * Type compatibility lists only LIVE-OBSERVED pairs; an unobserved combination never matches
 * (fail-closed → UNMATCHED, which is a normal result):
 *   RUN            <-> Run          TREADMILL_RUN  <-> VirtualRun     INDOOR_CYCLING <-> VirtualRide
 *
 * A composite match links only when EXACTLY ONE candidate passes; 0 → UNMATCHED, 2+ → AMBIGUOUS
 * (never auto-linked). Evidence deltas are reported for every method, as debugging facts.
 */
@Component
class IntervalsActivityMatcher {

    fun match(activity: Activity, candidates: List<IntervalsActivitySnapshot>): MatchResult {
        if (activity.externalSource == ExternalSource.GARMIN) {
            val garminId = activity.externalId
            val sourceIdMatches = candidates.filter { it.source == GARMIN_SOURCE_LABEL && it.externalId == garminId }
            when {
                sourceIdMatches.size == 1 ->
                    return MatchResult.Matched(sourceIdMatches[0], MatchMethod.SOURCE_ID, evidence(activity, sourceIdMatches[0]))
                sourceIdMatches.size > 1 -> return MatchResult.Ambiguous(sourceIdMatches.size)
            }
            val externalIdMatches = candidates.filter { it.externalId == garminId }
            when {
                externalIdMatches.size == 1 ->
                    return MatchResult.Matched(externalIdMatches[0], MatchMethod.EXTERNAL_ID, evidence(activity, externalIdMatches[0]))
                externalIdMatches.size > 1 -> return MatchResult.Ambiguous(externalIdMatches.size)
            }
        }
        val composite = candidates.filter { compositeCompatible(activity, it) }
        return when {
            composite.isEmpty() -> MatchResult.Unmatched
            composite.size == 1 -> MatchResult.Matched(composite[0], MatchMethod.COMPOSITE, evidence(activity, composite[0]))
            else -> MatchResult.Ambiguous(composite.size)
        }
    }

    private fun compositeCompatible(activity: Activity, candidate: IntervalsActivitySnapshot): Boolean {
        // A candidate that explicitly claims a DIFFERENT Garmin activity is never a composite match,
        // however similar its metrics look: its own identifier outranks any similarity.
        if (activity.externalSource == ExternalSource.GARMIN &&
            candidate.source == GARMIN_SOURCE_LABEL &&
            candidate.externalId != null &&
            candidate.externalId != activity.externalId
        ) {
            return false
        }
        val compatibleTypes = COMPATIBLE_TYPES[activity.activityType] ?: return false
        if (candidate.type !in compatibleTypes) return false
        val start = candidate.startDate ?: return false
        if (abs(start.epochSecond - activity.startedAt.epochSecond) > START_TOLERANCE_SECONDS) return false
        val elapsed = candidate.elapsedTimeSeconds ?: return false
        if (abs(elapsed - activity.durationSeconds) > DURATION_TOLERANCE_SECONDS) return false
        val ownDistance = activity.distanceMeters
        val theirDistance = candidate.distanceMeters
        if (ownDistance != null && ownDistance > 0 && theirDistance != null && theirDistance > 0 &&
            abs(theirDistance - ownDistance) > DISTANCE_TOLERANCE_METERS
        ) {
            return false
        }
        return true
    }

    private fun evidence(activity: Activity, snapshot: IntervalsActivitySnapshot) = MatchEvidence(
        startTimeDeltaSeconds = snapshot.startDate?.let { (it.epochSecond - activity.startedAt.epochSecond).toInt() },
        durationDeltaSeconds = snapshot.elapsedTimeSeconds?.let { it - activity.durationSeconds },
        distanceDeltaMeters = snapshot.distanceMeters?.let { theirs ->
            activity.distanceMeters?.let { ours -> theirs - ours }
        },
    )

    companion object {
        const val GARMIN_SOURCE_LABEL = "GARMIN_CONNECT"
        const val START_TOLERANCE_SECONDS = 30L
        const val DURATION_TOLERANCE_SECONDS = 5
        const val DISTANCE_TOLERANCE_METERS = 5.0

        /** Live-observed RunningAI <-> Intervals.icu type pairs only; nothing is inferred. */
        val COMPATIBLE_TYPES: Map<ActivityType, Set<String>> = mapOf(
            ActivityType.RUN to setOf("Run"),
            ActivityType.TREADMILL_RUN to setOf("VirtualRun"),
            ActivityType.INDOOR_CYCLING to setOf("VirtualRide"),
        )
    }
}
