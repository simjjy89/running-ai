package com.runningai.integration.mcp.read

import com.fasterxml.jackson.databind.ObjectMapper
import com.runningai.activity.Activity
import com.runningai.activity.ActivityRepository
import com.runningai.analysis.ActivityAnalysis
import com.runningai.analysis.RunningActivityAnalysisService
import com.runningai.athlete.AthleteService
import com.runningai.coach.CoachContextSerializer
import com.runningai.coach.SessionConstraints
import com.runningai.coach.TrainingContextTooLargeException
import com.runningai.coach.TrainingContextV2Builder
import com.runningai.coach.RecoveryContextBuilder
import com.runningai.enrichment.IntervalsEnrichmentStore
import com.runningai.injury.WorkoutSafetyGateService
import com.runningai.training.WorkoutRecommendationService
import io.modelcontextprotocol.server.McpStatelessServerFeatures
import io.modelcontextprotocol.spec.McpSchema
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/**
 * The seven READ-ONLY RunningAI MCP tools (Phase 7C-2a), registered only when
 * `running-ai.mcp.read-enabled=true` (see [McpReadToolConfig]) - an independent switch from the
 * write adapter's `running-ai.mcp.enabled`, so a read-only deployment never lists or reaches
 * `publish_workout`.
 *
 * Every tool is a thin adapter over an existing read-only service - no analysis formula is
 * re-implemented here, and NOTHING here can write: no repository save/delete, no Garmin or
 * Intervals call, no draft, no publish, no sync or re-analysis trigger (enforced by
 * `McpReadToolsArchitectureTest`). Responses are summaries of stored evidence; the raw 1 Hz
 * sample stream, GPS coordinates, internal/external identifiers and credentials are never
 * included. A metric the data cannot support is `null`/absent - never estimated.
 *
 * Error contract (uniform): failures return `isError=true` with
 * `{success:false, code, message}` where code is one of INVALID_ARGUMENT, ACTIVITY_NOT_FOUND,
 * ANALYSIS_NOT_FOUND, INSUFFICIENT_DATA, INTERNAL_ERROR. Messages carry no stack traces, no
 * internal identifiers and no secrets.
 */
class ReadMcpTools(
    private val athleteService: AthleteService,
    private val activities: ActivityRepository,
    private val analysisService: RunningActivityAnalysisService,
    private val contextBuilder: TrainingContextV2Builder,
    private val contextSerializer: CoachContextSerializer,
    private val recoveryContextBuilder: RecoveryContextBuilder,
    private val intervalsStore: IntervalsEnrichmentStore,
    private val recommendationService: WorkoutRecommendationService,
    private val safetyGate: WorkoutSafetyGateService,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(ReadMcpTools::class.java)

    fun specifications(): List<McpStatelessServerFeatures.SyncToolSpecification> = listOf(
        spec(recentActivitiesTool()) { args -> getRecentActivities(args) },
        spec(activityAnalysisTool()) { args -> getActivityAnalysis(args) },
        spec(trainingContextTool()) { args -> getTrainingContext(args) },
        spec(recoveryContextTool()) { args -> getRecoveryContext(args) },
        spec(fitnessTrendTool()) { args -> getFitnessTrend(args) },
        spec(workoutRecommendationTool()) { args -> getWorkoutRecommendation(args) },
        spec(athleteProfileTool()) { args -> getAthleteProfile(args) },
    )

    // ---- shared plumbing ----------------------------------------------------------------------

    /** Thrown internally to carry a machine-readable failure code to the uniform error result. */
    private class ToolFailure(val code: String, message: String) : RuntimeException(message)

    private fun spec(
        tool: McpSchema.Tool,
        handler: (Map<String, Any?>) -> Map<String, Any?>,
    ): McpStatelessServerFeatures.SyncToolSpecification =
        McpStatelessServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler { _, request ->
                val name = tool.name()
                try {
                    val body = handler(request.arguments() ?: emptyMap())
                    log.info("MCP read tool {}: ok", name)
                    result(body, isError = false)
                } catch (e: ToolFailure) {
                    log.info("MCP read tool {} refused: code={}", name, e.code)
                    result(linkedMapOf("success" to false, "code" to e.code, "message" to e.message), isError = true)
                } catch (e: TrainingContextTooLargeException) {
                    log.warn("MCP read tool {} failed: context too large", name)
                    result(linkedMapOf("success" to false, "code" to "INTERNAL_ERROR",
                        "message" to "The training context exceeded the configured size guard"), isError = true)
                } catch (e: RuntimeException) {
                    // Message deliberately generic: no stack trace, no internal detail leaves the server.
                    log.error("MCP read tool {} failed unexpectedly: {}", name, e.javaClass.simpleName)
                    result(linkedMapOf("success" to false, "code" to "INTERNAL_ERROR",
                        "message" to "Unexpected server error"), isError = true)
                }
            }
            .build()

    private fun result(body: Map<String, Any?>, isError: Boolean): McpSchema.CallToolResult =
        McpSchema.CallToolResult.builder()
            .addTextContent(objectMapper.writeValueAsString(body))
            .structuredContent(body)
            .isError(isError)
            .build()

    /** Marks every tool as what it genuinely is: read-only, non-destructive, idempotent, closed-world. */
    private fun readOnlyAnnotations(title: String) =
        McpSchema.ToolAnnotations(title, true, false, true, false, null)

    private fun schema(properties: Map<String, Any>, required: List<String> = emptyList()) =
        McpSchema.JsonSchema("object", properties, required, false, null, null)

    private fun fail(code: String, message: String): Nothing = throw ToolFailure(code, message)

    /** Rejects unknown argument keys so a typo never silently becomes "default behaviour". */
    private fun requireOnlyKeys(args: Map<String, Any?>, vararg allowed: String) {
        val unknown = args.keys - allowed.toSet()
        if (unknown.isNotEmpty()) {
            fail("INVALID_ARGUMENT", "Unknown argument(s): ${unknown.sorted().joinToString(", ")}")
        }
    }

    private fun intArg(args: Map<String, Any?>, key: String, default: Int, min: Int, max: Int): Int {
        val raw = args[key] ?: return default
        val value = (raw as? Number)?.toInt()
            ?: (raw as? String)?.toIntOrNull()
            ?: fail("INVALID_ARGUMENT", "$key must be an integer")
        if (value < min || value > max) fail("INVALID_ARGUMENT", "$key must be between $min and $max")
        return value
    }

    private fun dateArg(args: Map<String, Any?>, key: String): LocalDate? {
        val raw = args[key] ?: return null
        val text = raw as? String ?: fail("INVALID_ARGUMENT", "$key must be an ISO date string (YYYY-MM-DD)")
        return try {
            LocalDate.parse(text)
        } catch (e: DateTimeParseException) {
            fail("INVALID_ARGUMENT", "$key must be a valid ISO calendar date (YYYY-MM-DD)")
        }
    }

    private fun zone(): ZoneId = ZoneId.of(athleteService.getDefaultAthlete().timezone)

    private fun today(): LocalDate = LocalDate.now(clock.withZone(zone()))

    private fun activitiesBetween(from: LocalDate, toInclusive: LocalDate): List<Activity> {
        val z = zone()
        return activities.findByAthleteIdAndStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAtDesc(
            athleteService.getDefaultAthlete().id,
            from.atStartOfDay(z).toInstant(),
            toInclusive.plusDays(1).atStartOfDay(z).toInstant(),
        )
    }

    private fun localDateOf(a: Activity): LocalDate = a.startedAt.atZone(zone()).toLocalDate()

    @Suppress("UNCHECKED_CAST")
    private fun toMap(value: Any?): Map<String, Any?> =
        objectMapper.convertValue(value, LinkedHashMap::class.java) as Map<String, Any?>

    // ---- 1. get_recent_activities --------------------------------------------------------------

    private fun recentActivitiesTool() = McpSchema.Tool.builder()
        .name("get_recent_activities")
        .title("Recent running activities")
        .description("Lists the athlete's recently stored activities (newest first) with per-activity " +
            "flags for whether a RunningAI analysis and an Intervals.icu match exist. Dates are in the " +
            "athlete's own timezone; durations in seconds, distances in meters; a missing metric is " +
            "null, never estimated. Read-only: nothing is synced, changed or published.")
        .inputSchema(schema(mapOf(
            "days" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 90,
                "description" to "How many days back from today to include (default 14)"),
            "limit" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 20,
                "description" to "Maximum number of activities to return (default 10)"),
        )))
        .annotations(readOnlyAnnotations("Recent running activities"))
        .build()

    private fun getRecentActivities(args: Map<String, Any?>): Map<String, Any?> {
        requireOnlyKeys(args, "days", "limit")
        val days = intArg(args, "days", default = 14, min = 1, max = 90)
        val limit = intArg(args, "limit", default = 10, min = 1, max = 20)
        val today = today()
        val window = activitiesBetween(today.minusDays(days - 1L), today)
        val items = window.take(limit).map { a ->
            linkedMapOf(
                "date" to localDateOf(a).toString(),
                "activityType" to a.activityType.name,
                "durationSeconds" to a.durationSeconds,
                "distanceMeters" to a.distanceMeters,
                "averageHeartRateBpm" to a.averageHeartRate,
                "maxHeartRateBpm" to a.maxHeartRate,
                "analysisAvailable" to (analysisService.find(a.id) != null),
                "intervalsMatched" to (intervalsStore.findMetrics(a.id) != null),
            )
        }
        return linkedMapOf(
            "windowDays" to days,
            "totalInWindow" to window.size,
            "returned" to items.size,
            "activities" to items,
        )
    }

    // ---- 2. get_activity_analysis --------------------------------------------------------------

    private fun activityAnalysisTool() = McpSchema.Tool.builder()
        .name("get_activity_analysis")
        .title("Activity analysis (RunningAI)")
        .description("The stored RunningAI analysis of one activity: half-split speed/HR/cadence, " +
            "speed-HR decoupling (RunningAI-derived, no threshold), HR-zone time, LTHR exposure " +
            "seconds, lap spread, Garmin running dynamics (stride length cm, vertical oscillation cm, " +
            "vertical ratio %, ground contact time ms, running power W; lap-order halves) and interval " +
            "groups. Pass a date (athlete timezone) or omit it for the most recent activity. The " +
            "analysis is read as stored - this tool never recomputes it and never calls Garmin. " +
            "Null means the data does not support that metric.")
        .inputSchema(schema(mapOf(
            "date" to mapOf("type" to "string", "format" to "date",
                "description" to "Activity date (YYYY-MM-DD, athlete timezone); omit for the most recent activity"),
        )))
        .annotations(readOnlyAnnotations("Activity analysis (RunningAI)"))
        .build()

    private fun getActivityAnalysis(args: Map<String, Any?>): Map<String, Any?> {
        requireOnlyKeys(args, "date")
        val date = dateArg(args, "date")
        val activity = (if (date != null) activitiesBetween(date, date) else activitiesBetween(today().minusDays(89), today()))
            .firstOrNull()
            ?: fail("ACTIVITY_NOT_FOUND",
                if (date != null) "No stored activity on $date" else "No stored activity in the last 90 days")
        val analysis = analysisService.find(activity.id)
            ?: fail("ANALYSIS_NOT_FOUND", "Activity on ${localDateOf(activity)} has no stored analysis yet; " +
                "it is computed by an explicit operator action and this tool never triggers it")
        return linkedMapOf(
            "activity" to linkedMapOf(
                "date" to localDateOf(activity).toString(),
                "activityType" to activity.activityType.name,
                "durationSeconds" to activity.durationSeconds,
                "distanceMeters" to activity.distanceMeters,
                "averageHeartRateBpm" to activity.averageHeartRate,
                "maxHeartRateBpm" to activity.maxHeartRate,
            ),
            "analysis" to analysisBody(analysis),
        )
    }

    private fun analysisBody(a: ActivityAnalysis): Map<String, Any?> = linkedMapOf(
        "analysisVersion" to a.analysisVersion,
        "status" to a.status.name,
        "inputSampleCompleteness" to a.inputSampleCompleteness?.name,
        "computedAt" to a.computedAt.toString(),
        "session" to toMap(a.session),
        "heartRateZones" to linkedMapOf(
            "percentByZone" to a.zones.zonePercent,
            "secondsByZone" to a.zones.zoneSeconds,
            "totalSeconds" to a.zones.totalSeconds,
        ),
        "lactateThresholdExposureSeconds" to toMap(a.threshold),
        "laps" to toMap(a.laps),
        // Garmin running dynamics (Phase 7B-3): halves are lap-order halves, deliberately distinct
        // from the elapsed-time session halves above; the description says so.
        "runningDynamics" to toMap(a.runningDynamics),
        "intervalGroups" to a.intervalGroups.take(MAX_INTERVAL_GROUPS).map { g ->
            linkedMapOf(
                "workRepCount" to g.workRepCount,
                "meanSpeed" to g.meanSpeed,
                "speedCvPercent" to g.speedCvPercent,
                "lastVsFirstSpeedChangePercent" to g.lastVsFirstSpeedChangePercent,
                "hrProgressionBpm" to g.hrProgressionBpm,
                "recoveryHrDropBpm" to g.recovery.dropBpm,
                "recoveryDurationSeconds" to g.recovery.durationSeconds,
            )
        },
    )

    // ---- 3. get_training_context ---------------------------------------------------------------

    private fun trainingContextTool() = McpSchema.Tool.builder()
        .name("get_training_context")
        .title("Training context (V2)")
        .description("The full TrainingContextV2 snapshot the AI coach would be given for a date " +
            "(default today): data coverage counts, Garmin recovery vs 28-day baselines, Intervals.icu " +
            "CTL (calculated fitness) / ATL (calculated fatigue) / derivedForm (RunningAI-derived " +
            "ctl-atl), training rhythm, recent-activity evidence with running dynamics, source " +
            "freshness and lactate-threshold provenance. Built from stored data only - no Garmin or " +
            "Intervals call, nothing recomputed. Null means genuinely unknown.")
        .inputSchema(schema(mapOf(
            "date" to mapOf("type" to "string", "format" to "date",
                "description" to "Session date (YYYY-MM-DD, athlete timezone); omit for today"),
        )))
        .annotations(readOnlyAnnotations("Training context (V2)"))
        .build()

    private fun getTrainingContext(args: Map<String, Any?>): Map<String, Any?> {
        requireOnlyKeys(args, "date")
        val date = dateArg(args, "date") ?: today()
        val context = contextBuilder.build(date, SessionConstraints())
        // The canonical serializer is reused on purpose: its output is exactly what the coach sees,
        // already free of GPS/identifiers/credentials, deterministic, and size-guarded.
        val snapshot = contextSerializer.snapshot(context, Instant.now(clock))
        return linkedMapOf(
            "date" to date.toString(),
            "contextVersion" to "V2",
            "sizeBytes" to snapshot.json.toByteArray(Charsets.UTF_8).size,
            "sha256" to snapshot.sha256,
            "context" to objectMapper.readValue(snapshot.json, LinkedHashMap::class.java),
        )
    }

    // ---- 4. get_recovery_context ---------------------------------------------------------------

    private fun recoveryContextTool() = McpSchema.Tool.builder()
        .name("get_recovery_context")
        .title("Recovery context (Garmin)")
        .description("Stored Garmin recovery metrics for a date (default today) - HRV ms, sleep hours " +
            "and score, resting HR bpm, Body Battery, stress - each with its own recording date, age " +
            "in days, 28-day personal baseline and baseline validity. Measurement only: RunningAI " +
            "never rates recovery as good or bad. A metric Garmin never reported is null; nothing is " +
            "estimated and nothing is synced by this call.")
        .inputSchema(schema(mapOf(
            "date" to mapOf("type" to "string", "format" to "date",
                "description" to "Date (YYYY-MM-DD, athlete timezone); omit for today"),
        )))
        .annotations(readOnlyAnnotations("Recovery context (Garmin)"))
        .build()

    private fun getRecoveryContext(args: Map<String, Any?>): Map<String, Any?> {
        requireOnlyKeys(args, "date")
        val date = dateArg(args, "date") ?: today()
        val recovery = recoveryContextBuilder.build(date)
        return linkedMapOf(
            "date" to date.toString(),
            "anyMetricAvailable" to recovery.anyAvailable,
            "recovery" to toMap(recovery),
        )
    }

    // ---- 5. get_fitness_trend ------------------------------------------------------------------

    private fun fitnessTrendTool() = McpSchema.Tool.builder()
        .name("get_fitness_trend")
        .title("Fitness trend (Intervals.icu)")
        .description("Stored Intervals.icu fitness-model days in a date range (max 90 days): per day " +
            "CTL (Intervals' calculated fitness), ATL (Intervals' calculated fatigue) and derivedForm " +
            "(ctl - atl, explicitly RunningAI-derived - Form has no live Intervals API field). Days " +
            "never stored are simply absent - no interpolation. Read-only; no Intervals call happens.")
        .inputSchema(schema(mapOf(
            "oldestDate" to mapOf("type" to "string", "format" to "date", "description" to "Range start (YYYY-MM-DD)"),
            "newestDate" to mapOf("type" to "string", "format" to "date", "description" to "Range end (YYYY-MM-DD)"),
        ), required = listOf("oldestDate", "newestDate")))
        .annotations(readOnlyAnnotations("Fitness trend (Intervals.icu)"))
        .build()

    private fun getFitnessTrend(args: Map<String, Any?>): Map<String, Any?> {
        requireOnlyKeys(args, "oldestDate", "newestDate")
        val oldest = dateArg(args, "oldestDate") ?: fail("INVALID_ARGUMENT", "oldestDate is required (YYYY-MM-DD)")
        val newest = dateArg(args, "newestDate") ?: fail("INVALID_ARGUMENT", "newestDate is required (YYYY-MM-DD)")
        if (newest.isBefore(oldest)) fail("INVALID_ARGUMENT", "newestDate must not be before oldestDate")
        val span = ChronoUnit.DAYS.between(oldest, newest) + 1
        if (span > MAX_FITNESS_SPAN_DAYS) {
            fail("INVALID_ARGUMENT", "The range covers $span days; at most $MAX_FITNESS_SPAN_DAYS days per call")
        }
        val days = intervalsStore.fitnessDays(athleteService.getDefaultAthlete().id, oldest, newest)
        return linkedMapOf(
            "oldestDate" to oldest.toString(),
            "newestDate" to newest.toString(),
            "daysStored" to days.size,
            "terminology" to "ctl = Intervals.icu calculated fitness; atl = Intervals.icu calculated " +
                "fatigue; derivedForm = ctl - atl, RunningAI-derived",
            "days" to days.map { d ->
                linkedMapOf("date" to d.date.toString(), "ctl" to d.ctl, "atl" to d.atl, "derivedForm" to d.derivedForm)
            },
        )
    }

    // ---- 6. get_workout_recommendation ---------------------------------------------------------

    private fun workoutRecommendationTool() = McpSchema.Tool.builder()
        .name("get_workout_recommendation")
        .title("Workout recommendation (deterministic)")
        .description("RunningAI's deterministic, rule-based workout recommendation for a date " +
            "(default today): one intent (REST/RECOVERY/EASY/LONG/CROSS_TRAINING; QUALITY is never " +
            "auto-selected), a duration range and the calendar-pattern reasons. This is NOT an AI-coach " +
            "prescription and NOTHING is stored or published by this call. The athlete's injury/pain " +
            "safety state is checked first: while it forbids non-REST training, no training " +
            "recommendation is returned - only the safety decision and what confirmation is needed.")
        .inputSchema(schema(mapOf(
            "date" to mapOf("type" to "string", "format" to "date",
                "description" to "Date (YYYY-MM-DD, athlete timezone); omit for today"),
        )))
        .annotations(readOnlyAnnotations("Workout recommendation (deterministic)"))
        .build()

    private fun getWorkoutRecommendation(args: Map<String, Any?>): Map<String, Any?> {
        requireOnlyKeys(args, "date")
        val date = dateArg(args, "date") ?: today()
        // Phase 7B-2 injury gate, applied with the same meaning as every workout-producing path:
        // while only REST is allowed, this tool does not "recommend" a training session at all -
        // it reports the safety decision instead. It never fabricates a REST prescription.
        val decision = safetyGate.currentDecision()
        val safety = linkedMapOf(
            "nonRestTrainingAllowed" to decision.nonRestTrainingAllowed,
            "reasonCode" to decision.reasonCode.name,
            "message" to decision.message,
            "evaluatedAt" to decision.evaluatedAt.toString(),
        )
        if (!decision.nonRestTrainingAllowed) {
            return linkedMapOf(
                "date" to date.toString(),
                "injurySafety" to safety,
                "recommendation" to null,
                "suppressedReason" to "The injury/pain safety state currently allows REST only, so no " +
                    "training recommendation is provided. Resolve or refresh the injury report first.",
            )
        }
        val r = recommendationService.recommend(date)
        return linkedMapOf(
            "date" to date.toString(),
            "injurySafety" to safety,
            "recommendation" to linkedMapOf(
                "intent" to r.recommendedIntent().name,
                "durationMinMinutes" to r.durationMinMinutes(),
                "durationMaxMinutes" to r.durationMaxMinutes(),
                "intensityClass" to r.intensityClass().name,
                "confidence" to r.confidence().name,
                "dataSufficiency" to r.dataSufficiency().name,
                "reasons" to r.reasons().map { it.name },
                "summary" to r.summary(),
            ),
            "note" to "Deterministic calendar-pattern recommendation; not an AI-coach prescription, " +
                "and nothing was stored, synced or published by this call.",
        )
    }

    // ---- 7. get_athlete_profile ----------------------------------------------------------------

    private fun athleteProfileTool() = McpSchema.Tool.builder()
        .name("get_athlete_profile")
        .title("Athlete intensity profile")
        .description("The athlete's stored lactate-threshold heart rate (bpm) and threshold pace " +
            "(seconds per km), with provenance: when the profile last changed and an explicit warning " +
            "that the SOURCE (manual entry vs Garmin auto-sync) is not tracked in this build - treat " +
            "the value's origin as unconfirmed. Values are read as stored; this tool never syncs from " +
            "or writes to Garmin. Device heart-rate zone boundaries are not athlete state in RunningAI " +
            "(they are stored per activity) and are not part of this profile.")
        .inputSchema(schema(emptyMap()))
        .annotations(readOnlyAnnotations("Athlete intensity profile"))
        .build()

    private fun getAthleteProfile(args: Map<String, Any?>): Map<String, Any?> {
        requireOnlyKeys(args)
        val provenance = contextBuilder.lactateThresholdProvenance(today())
        return linkedMapOf(
            "lactateThresholdHeartRateBpm" to provenance.lactateThresholdHeartRateBpm,
            "lactateThresholdPaceSecondsPerKm" to provenance.lactateThresholdPaceSecondsPerKm,
            "initialized" to (provenance.lactateThresholdHeartRateBpm != null ||
                provenance.lactateThresholdPaceSecondsPerKm != null),
            "provenance" to linkedMapOf(
                "sourceTracked" to provenance.sourceTracked,
                "profileLastUpdatedAt" to provenance.profileLastUpdatedAt?.toString(),
                "profileAgeDays" to provenance.profileAgeDays,
                "warning" to provenance.warning,
            ),
        )
    }

    private companion object {
        const val MAX_FITNESS_SPAN_DAYS = 90L
        /** Same cap as the coach context's default: summaries, never a repetition dump. */
        const val MAX_INTERVAL_GROUPS = 3
    }
}
