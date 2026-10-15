package com.runningai.analysis

import com.runningai.activity.Activity
import com.runningai.activity.ActivityRepository
import com.runningai.activity.MetricAvailability
import com.runningai.activity.detail.ActivityDetailStore
import com.runningai.athlete.AthleteService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Builds the descriptive history report (Phase 8B, Workstream F).
 *
 * Everything comes from rows that already exist: `activity` for what was done, `activity_analysis`
 * for what the engine derived from it. Nothing is fetched, nothing is analysed on read and nothing
 * is written - an activity with no stored analysis is counted as unanalysed rather than quietly
 * analysed, because analysing here would make a GET expensive and non-deterministic.
 *
 * Three rules it follows throughout, inherited from this package:
 *  - **Measurement only.** A trend is "this was X, then it was Y, the difference is Z". Whether Z
 *    is good is a judgement, and judgements belong to the coach.
 *  - **Missing data is missing**, never estimated, interpolated or filled from a neighbour. A
 *    metric with nothing in one half of the history is reported as not comparable, not as zero
 *    change.
 *  - **Thin evidence stays visibly thin.** A trend computed from two activities is reported with
 *    its sample count rather than suppressed or presented like one computed from fifty.
 */
@Service
class TrainingAnalysisReportService(
    private val activities: ActivityRepository,
    private val analysisStore: ActivityAnalysisStore,
    private val detailStore: ActivityDetailStore,
    private val athletes: AthleteService,
    private val clock: Clock,
) {

    private companion object {
        /** Default number of equal slices the history is divided into for the volume trend. */
        const val DEFAULT_PERIODS = 6
        const val MAX_PERIODS = 24

        /**
         * Standard distances, with the tolerance inside which a stored activity counts as "about"
         * that distance. Wide enough to catch a real 10 km run measured as 9.9 or 10.2, narrow
         * enough that a 15 km run never lands in the 10 km bucket.
         */
        val STANDARD_DISTANCES = listOf(
            Triple("5K", 5.0, 3.0),
            Triple("10K", 10.0, 3.0),
            Triple("HALF_MARATHON", 21.0975, 3.0),
            Triple("MARATHON", 42.195, 3.0),
        )

        val NOTES = listOf(
            "This report measures; it does not judge. No value here is a rating, a score or a target.",
            "A best effort here is the fastest WHOLE stored activity of about that distance - not a " +
                "race result and not a verified personal best.",
            "A trend is the difference between the earlier and later halves of the stored history. " +
                "It cannot distinguish a change in fitness from a change in what was being trained.",
            "Metrics listed under metricUnavailability were never recorded for those sessions. An " +
                "absent metric is not a low one.",
        )
    }

    @Transactional(readOnly = true)
    fun build(requestedPeriods: Int?): TrainingAnalysisReport {
        val athlete = athletes.getDefaultAthlete()
        val zone = ZoneId.of(athlete.timezone)
        val today = LocalDate.now(clock.withZone(zone))
        val periods = (requestedPeriods ?: DEFAULT_PERIODS).coerceIn(1, MAX_PERIODS)

        val all = activities.findAllByOrderByStartedAtDesc().filter { it.athleteId == athlete.id }
        val runs = all.filter { it.activityType in REPORTED_RUN_TYPES }.sortedBy { it.startedAt }
        val analyses = runs.associate { it.id to analysisStore.find(it.id) }

        val dates = runs.map { it.startedAt.atZone(zone).toLocalDate() }
        val oldest = dates.minOrNull()
        val newest = dates.maxOrNull()

        return TrainingAnalysisReport(
            generatedFor = today,
            coverage = ReportCoverage(
                oldestActivityDate = oldest,
                newestActivityDate = newest,
                historySpanDays = if (oldest != null && newest != null) {
                    (newest.toEpochDay() - oldest.toEpochDay()).toInt() + 1
                } else {
                    null
                },
                activityCount = all.size,
                runningActivityCount = runs.size,
                analysedActivityCount = analyses.values.count { it != null },
                activityCountBySource = all.groupingBy { it.externalSource }.eachCount(),
            ),
            volume = volumePeriods(runs, zone, periods, oldest, newest),
            bestEfforts = bestEfforts(runs, zone),
            durability = DurabilityTrend(
                speedChangePercent = trend(runs, analyses) { it.session.halves.speedChangePercent },
                hrChangePercent = trend(runs, analyses) { it.session.halves.hrChangePercent },
                speedHrDecouplingPercent = trend(runs, analyses) { it.session.halves.speedHrDecouplingPercent },
            ),
            runningDynamics = DynamicsTrend(
                cadenceSpm = trend(runs, analyses) { it.laps.cadenceMean },
                strideLengthCm = trend(runs, analyses) { it.runningDynamics.avgStrideLengthCm },
                groundContactTimeMs = trend(runs, analyses) { it.runningDynamics.avgGroundContactTimeMs },
                verticalOscillationCm = trend(runs, analyses) { it.runningDynamics.avgVerticalOscillationCm },
            ),
            limitations = ReportLimitations(
                unanalysedActivityCount = analyses.values.count { it == null },
                partialAnalysisCount = analyses.values.count { it != null && it.status != AnalysisStatus.COMPLETE },
                metricUnavailability = MetricAvailability.notesFor(
                    detailStore.activityIdsWithLaps(all.map { it.id }).let { withLaps ->
                        all.map {
                            MetricAvailability.ActivityMetricSource(it.externalSource, it.id in withLaps)
                        }
                    },
                ),
                notes = NOTES,
            ),
        )
    }

    /**
     * The history split into equal-length slices, oldest first. Equal lengths are what make two
     * slices comparable; the alternative - calendar months - would silently compare 28 days against
     * 31 and call the difference a trend.
     */
    private fun volumePeriods(
        runs: List<Activity>,
        zone: ZoneId,
        periods: Int,
        oldest: LocalDate?,
        newest: LocalDate?,
    ): List<VolumePeriod> {
        if (oldest == null || newest == null) {
            return emptyList()
        }
        val totalDays = (newest.toEpochDay() - oldest.toEpochDay()).toInt() + 1
        // Ceiling, not floor: flooring leaves a remainder that becomes an extra, shorter slice at
        // the end, and a short slice sitting beside full ones reads as a drop in volume.
        val sliceDays = maxOf(1, (totalDays + periods - 1) / periods)
        val result = mutableListOf<VolumePeriod>()
        var start: LocalDate = oldest
        while (!start.isAfter(newest)) {
            val end = minOf(start.plusDays(sliceDays - 1L), newest)
            val inSlice = runs.filter {
                val d = it.startedAt.atZone(zone).toLocalDate()
                !d.isBefore(start) && !d.isAfter(end)
            }
            val distances = inSlice.mapNotNull { it.distanceMeters }
            result += VolumePeriod(
                startDate = start,
                endDate = end,
                activityCount = inSlice.size,
                // null, not 0.0: "no activity recorded a distance" and "0 km was run" differ.
                totalDistanceKm = distances.takeIf { it.isNotEmpty() }?.sum()?.let { round(it / 1000.0, 2) },
                totalDurationMinutes = inSlice.sumOf { it.durationSeconds }.toLong() / 60,
                longestRunKm = distances.maxOrNull()?.let { round(it / 1000.0, 2) },
            )
            start = end.plusDays(1)
        }
        return result
    }

    private fun bestEfforts(runs: List<Activity>, zone: ZoneId): List<BestEffort> =
        STANDARD_DISTANCES.map { (label, targetKm, tolerancePercent) ->
            val matching = runs.filter { activity ->
                val km = activity.distanceMeters?.div(1000.0) ?: return@filter false
                activity.durationSeconds > 0 && abs(km - targetKm) / targetKm * 100.0 <= tolerancePercent
            }
            // Compared by PACE, not by elapsed time: a 10.2 km run and a 9.9 km run are both in the
            // bucket, and the longer one finishing later does not make it the slower effort.
            val fastest = matching.minByOrNull { pacePerKm(it) ?: Double.MAX_VALUE }
            val mostRecent = matching.maxByOrNull { it.startedAt }
            BestEffort(
                label = label,
                targetDistanceKm = targetKm,
                tolerancePercent = tolerancePercent,
                matchingActivityCount = matching.size,
                fastestDate = fastest?.startedAt?.atZone(zone)?.toLocalDate(),
                fastestDistanceKm = fastest?.distanceMeters?.let { round(it / 1000.0, 2) },
                fastestDurationSeconds = fastest?.durationSeconds,
                fastestPaceSecondsPerKm = fastest?.let { pacePerKm(it)?.roundToInt() },
                mostRecentDate = mostRecent?.startedAt?.atZone(zone)?.toLocalDate(),
                mostRecentPaceSecondsPerKm = mostRecent?.let { pacePerKm(it)?.roundToInt() },
            )
        }

    private fun pacePerKm(activity: Activity): Double? {
        val km = activity.distanceMeters?.div(1000.0) ?: return null
        if (km <= 0 || activity.durationSeconds <= 0) {
            return null
        }
        return activity.durationSeconds / km
    }

    /**
     * Earlier half versus later half of the activities that actually carried [metric], in
     * chronological order.
     *
     * Split by position among the activities that HAVE the metric, not by calendar date: dynamics
     * exist only on Garmin-sourced runs, and splitting by date would put every one of them in the
     * later half and report "not comparable" for a metric with plenty of data.
     */
    private fun trend(
        runs: List<Activity>,
        analyses: Map<Long, ActivityAnalysis?>,
        metric: (ActivityAnalysis) -> Double?,
    ): MetricTrend {
        val values = runs.mapNotNull { analyses[it.id]?.let(metric) }
        if (values.isEmpty()) {
            return MetricTrend(0, null, null, null, null, comparable = false)
        }
        val half = values.size / 2
        val earlier = values.take(half)
        val later = values.drop(half)
        val earlierMean = earlier.takeIf { it.isNotEmpty() }?.average()?.let { round(it, 2) }
        val laterMean = later.takeIf { it.isNotEmpty() }?.average()?.let { round(it, 2) }
        return MetricTrend(
            sampleCount = values.size,
            earlierMean = earlierMean,
            laterMean = laterMean,
            change = if (earlierMean != null && laterMean != null) round(laterMean - earlierMean, 2) else null,
            overallMean = round(values.average(), 2),
            // A single activity cannot show a trend, and saying so is better than reporting a
            // change of zero against an empty earlier half.
            comparable = earlierMean != null && laterMean != null,
        )
    }

    private fun round(value: Double, decimals: Int): Double {
        var factor = 1.0
        repeat(decimals) { factor *= 10 }
        return (value * factor).roundToInt() / factor
    }
}

@RestController
class TrainingAnalysisReportController(private val service: TrainingAnalysisReportService) {

    /**
     * A descriptive report over the whole stored history. Read-only and database-only: no Garmin
     * call, no Intervals call, no analysis triggered and nothing written.
     *
     * @param periods how many equal slices the volume trend is divided into (1..24, default 6)
     */
    @GetMapping("/api/v1/training-analysis-report")
    fun report(@RequestParam(required = false) periods: Int?): TrainingAnalysisReport = service.build(periods)
}
