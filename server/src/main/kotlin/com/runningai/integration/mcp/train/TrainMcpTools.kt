package com.runningai.integration.mcp.train

import com.fasterxml.jackson.databind.ObjectMapper
import com.runningai.coach.AiCoachException
import com.runningai.coach.SessionConstraints
import com.runningai.coach.TrainingContextTooLargeException
import com.runningai.coach.TrainingEnvironment
import com.runningai.coach.WorkoutDateAlreadyApprovedException
import com.runningai.coach.WorkoutDraftApprovalResponse
import com.runningai.coach.WorkoutDraftApprovalService
import com.runningai.coach.WorkoutDraftApprovedImmutableException
import com.runningai.coach.WorkoutDraftNotFoundException
import com.runningai.coach.WorkoutDraftResponse
import com.runningai.coach.WorkoutDraftService
import com.runningai.coach.WorkoutDraftStatus
import com.runningai.coach.WorkoutDraftSupersededException
import com.runningai.draftpublish.ApprovedWorkoutDraftPublishService
import com.runningai.draftpublish.DraftPublishAlreadyRunningException
import com.runningai.draftpublish.DraftPublishPreviewResponse
import com.runningai.draftpublish.DraftPublishResponse
import com.runningai.draftpublish.DraftPublishingDisabledException
import com.runningai.draftpublish.UnpublishableDraftException
import com.runningai.draftpublish.WorkoutDraftNotApprovedException
import com.runningai.injury.WorkoutSafetyViolationException
import com.runningai.integration.intervals.IntervalsException
import io.modelcontextprotocol.server.McpStatelessServerFeatures
import io.modelcontextprotocol.spec.McpSchema
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * The six TRAINING-LIFECYCLE RunningAI MCP tools (Phase 7F-1), registered only when
 * `running-ai.mcp.train-enabled=true` (see [McpTrainToolConfig]) - a third switch, independent of
 * the read tools' `running-ai.mcp.read-enabled` and the legacy write adapter's
 * `running-ai.mcp.enabled`, so a read-only deployment never lists or reaches any of these.
 *
 *     create_workout_draft -> get_workout_draft -> (revise_workout_draft)* ->
 *     approve_workout_draft -> preview_draft_publish -> publish_approved_draft
 *
 * **Every tool is a thin adapter over the service the REST API already calls**, and the response
 * bodies are the REST DTOs themselves ([WorkoutDraftResponse], [DraftPublishPreviewResponse], ...)
 * so the two surfaces can never drift apart. No lifecycle rule is re-implemented here: approval
 * immutability, one approval per athlete and date, the injury safety gate, the publishing master
 * switch, per-draft single flight and "a re-publish returns the stored result" all live in the
 * services and apply identically whichever surface called them (enforced by
 * [com.runningai.integration.mcp.train.TrainMcpToolsArchitectureTest]).
 *
 * **Nothing here invents a workout.** When the AI coach is unavailable, times out, answers with
 * something that is not the agreed contract, or fails hard-safety validation, the call FAILS with
 * that reason - a plausible-looking session is never substituted (`AI_COACH_*` below).
 *
 * **An MCP call is not the athlete's approval.** `approve_workout_draft` and
 * `publish_approved_draft` are the only two steps that commit, and each additionally requires
 * `confirmedByAthlete: true` plus an echo of a value the caller can only have obtained by first
 * READING the draft (its current `version`, and the `approvalId`). A model that has not shown the
 * athlete the draft cannot supply those, so neither step can be reached blindly or as a side
 * effect of a suggestion. Both are also annotated `readOnlyHint=false`, which is what makes an MCP
 * client ask its user before calling. Neither mechanism *proves* a human agreed - that comes from
 * the client's own confirmation UI - but together they make an accidental or implicit approval
 * impossible.
 *
 * Error contract (uniform, and deliberately the SAME codes the REST API returns for the same
 * failures): `isError=true` with `{success:false, code, message, correlationId}`. Messages carry no
 * prompt text, no raw AI response, no credential and no stack trace.
 */
class TrainMcpTools(
    private val draftService: WorkoutDraftService,
    private val approvalService: WorkoutDraftApprovalService,
    private val publishService: ApprovedWorkoutDraftPublishService,
    private val objectMapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(TrainMcpTools::class.java)

    fun specifications(): List<McpStatelessServerFeatures.SyncToolSpecification> = listOf(
        spec(createDraftTool()) { args, cid -> createWorkoutDraft(args, cid) },
        spec(getDraftTool()) { args, cid -> getWorkoutDraft(args, cid) },
        spec(reviseDraftTool()) { args, cid -> reviseWorkoutDraft(args, cid) },
        spec(approveDraftTool()) { args, cid -> approveWorkoutDraft(args, cid) },
        spec(previewPublishTool()) { args, cid -> previewDraftPublish(args, cid) },
        spec(publishTool()) { args, cid -> publishApprovedDraft(args, cid) },
    )

    // ---- shared plumbing ----------------------------------------------------------------------

    /** Thrown internally to carry a machine-readable failure code to the uniform error result. */
    private class ToolFailure(val code: String, message: String) : RuntimeException(message)

    private fun spec(
        tool: McpSchema.Tool,
        handler: (Map<String, Any?>, String) -> Map<String, Any?>,
    ): McpStatelessServerFeatures.SyncToolSpecification =
        McpStatelessServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler { _, request ->
                val name = tool.name()
                // One id per call, returned to the caller AND written to every log line this call
                // produces, so a report of "the publish I ran at 09:12" can be traced to the exact
                // server-side decision without the athlete having to quote a draft id.
                val correlationId = UUID.randomUUID().toString()
                try {
                    val body = handler(request.arguments() ?: emptyMap(), correlationId)
                    log.info("MCP train tool {}: ok cid={}", name, correlationId)
                    result(body + ("correlationId" to correlationId), isError = false)
                } catch (e: ToolFailure) {
                    log.info("MCP train tool {} refused: code={} cid={}", name, e.code, correlationId)
                    failure(e.code, e.message, correlationId)
                } catch (e: RuntimeException) {
                    val mapped = mapDomainFailure(e)
                    if (mapped != null) {
                        log.info("MCP train tool {} failed: code={} cid={}", name, mapped.first, correlationId)
                        failure(mapped.first, mapped.second, correlationId, mapped.third)
                    } else {
                        // Message deliberately generic: no stack trace, no internal detail leaves the server.
                        log.error("MCP train tool {} failed unexpectedly: {} cid={}", name, e.javaClass.simpleName, correlationId)
                        failure("INTERNAL_ERROR", "Unexpected server error", correlationId)
                    }
                }
            }
            .build()

    /**
     * Maps the domain's own exceptions onto the SAME codes
     * `WorkoutDraftExceptionHandler`/`DraftPublishExceptionHandler` return over HTTP, so a failure
     * reads identically whether it arrived over REST or over MCP.
     */
    private fun mapDomainFailure(e: RuntimeException): Triple<String, String, List<String>?>? = when (e) {
        is WorkoutDraftNotFoundException -> t("WORKOUT_DRAFT_NOT_FOUND", e)
        is WorkoutDraftSupersededException -> t("WORKOUT_DRAFT_SUPERSEDED", e)
        is WorkoutDraftApprovedImmutableException -> t("WORKOUT_DRAFT_APPROVED_IMMUTABLE", e)
        is WorkoutDateAlreadyApprovedException -> t("WORKOUT_DATE_ALREADY_APPROVED", e)
        is WorkoutDraftNotApprovedException ->
            if (e.status == WorkoutDraftStatus.SUPERSEDED) t("WORKOUT_DRAFT_SUPERSEDED", e)
            else t("WORKOUT_DRAFT_APPROVAL_REQUIRED", e)
        is DraftPublishingDisabledException -> t("DRAFT_PUBLISHING_DISABLED", e)
        is DraftPublishAlreadyRunningException -> t("DRAFT_PUBLISH_ALREADY_RUNNING", e)
        is UnpublishableDraftException -> Triple(
            "UNPUBLISHABLE_DRAFT",
            "The approved draft cannot be published without changing it; nothing was sent",
            e.reasons,
        )
        // Fail closed: the injury/pain policy forbids this, and nothing was stored or sent.
        is WorkoutSafetyViolationException -> t("WORKOUT_SAFETY_${e.decision.reasonCode.name}", e)
        is TrainingContextTooLargeException ->
            Triple("TRAINING_CONTEXT_TOO_LARGE", "The training context exceeded the configured size guard", null)
        // The coach could not produce a usable draft. NOTHING is substituted - see the class comment.
        is AiCoachException -> Triple("AI_COACH_${e.reason.name}", coachMessage(e), null)
        is IntervalsException -> t(e.code, e)
        else -> null
    }

    private fun t(code: String, e: RuntimeException): Triple<String, String, List<String>?> =
        Triple(code, e.message ?: code, null)

    /** Actionable, operator-readable causes. Never includes the prompt, the response or a credential. */
    private fun coachMessage(e: AiCoachException): String = when (e.reason) {
        AiCoachException.Reason.PROVIDER_UNAVAILABLE ->
            "The local AI coach CLI is not installed or not on this server's PATH, so no draft was " +
                "created. Install it on the RunningAI host (or set running-ai.coach.claude.command) " +
                "and retry; no workout was invented in its place."
        AiCoachException.Reason.AUTH_REQUIRED ->
            "The local AI coach CLI is installed but has no usable login session on the RunningAI " +
                "host, so no draft was created. Log it in on that machine and retry."
        AiCoachException.Reason.TIMEOUT ->
            "The AI coach exceeded its configured timeout and was stopped; no draft was created."
        AiCoachException.Reason.PROVIDER_ERROR ->
            "The AI coach ran but reported a failure; no draft was created."
        AiCoachException.Reason.INVALID_RESPONSE ->
            "The AI coach answered with something other than the agreed workout contract, so the " +
                "response was rejected rather than guessed at; no draft was created."
        AiCoachException.Reason.VALIDATION_FAILED ->
            "The AI coach's workout failed RunningAI's hard-safety bounds and was rejected; no draft was created."
    }

    private fun failure(
        code: String,
        message: String?,
        correlationId: String,
        reasons: List<String>? = null,
    ): McpSchema.CallToolResult {
        val body = linkedMapOf<String, Any?>(
            "success" to false,
            "code" to code,
            "message" to (message ?: code),
            "correlationId" to correlationId,
        )
        if (reasons != null) body["reasons"] = reasons
        return result(body, isError = true)
    }

    private fun result(body: Map<String, Any?>, isError: Boolean): McpSchema.CallToolResult =
        McpSchema.CallToolResult.builder()
            .addTextContent(objectMapper.writeValueAsString(body))
            .structuredContent(body)
            .isError(isError)
            .build()

    /**
     * Honest annotations: only the two inspection tools are read-only. `openWorldHint` is true for
     * the publish tool alone - it is the one step that talks to a system outside RunningAI.
     * `destructiveHint` is false throughout: this lifecycle only ever adds rows and calendar
     * entries, it never deletes an activity or an existing workout it does not own.
     */
    private fun readOnlyAnnotations(title: String) =
        McpSchema.ToolAnnotations(title, true, false, true, false, null)

    private fun writeAnnotations(title: String, idempotent: Boolean, openWorld: Boolean = false) =
        McpSchema.ToolAnnotations(title, false, false, idempotent, openWorld, null)

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

    private fun longArg(args: Map<String, Any?>, key: String): Long {
        val raw = args[key] ?: fail("INVALID_ARGUMENT", "$key is required")
        return (raw as? Number)?.toLong()
            ?: (raw as? String)?.toLongOrNull()
            ?: fail("INVALID_ARGUMENT", "$key must be an integer")
    }

    private fun optionalIntArg(args: Map<String, Any?>, key: String, min: Int, max: Int): Int? {
        val raw = args[key] ?: return null
        val value = (raw as? Number)?.toInt()
            ?: (raw as? String)?.toIntOrNull()
            ?: fail("INVALID_ARGUMENT", "$key must be an integer")
        if (value < min || value > max) fail("INVALID_ARGUMENT", "$key must be between $min and $max")
        return value
    }

    private fun requiredIntArg(args: Map<String, Any?>, key: String): Int {
        args[key] ?: fail("INVALID_ARGUMENT", "$key is required")
        return optionalIntArg(args, key, Int.MIN_VALUE, Int.MAX_VALUE)!!
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

    private fun textArg(args: Map<String, Any?>, key: String, required: Boolean = false): String? {
        val raw = args[key] ?: return if (required) fail("INVALID_ARGUMENT", "$key is required") else null
        val text = (raw as? String) ?: fail("INVALID_ARGUMENT", "$key must be a string")
        if (text.isBlank()) {
            return if (required) fail("INVALID_ARGUMENT", "$key must not be blank") else null
        }
        if (text.length > MAX_TEXT_LENGTH) {
            fail("INVALID_ARGUMENT", "$key must be at most $MAX_TEXT_LENGTH characters")
        }
        return text
    }

    private fun environmentArg(args: Map<String, Any?>): TrainingEnvironment? {
        val raw = args["environment"] ?: return null
        val text = (raw as? String) ?: fail("INVALID_ARGUMENT", "environment must be a string")
        return TrainingEnvironment.entries.firstOrNull { it.name.equals(text, ignoreCase = true) }
            ?: fail("INVALID_ARGUMENT",
                "environment must be one of ${TrainingEnvironment.entries.joinToString(", ") { it.name }}")
    }

    /**
     * The athlete's own explicit confirmation, required on the two committing steps. Absent or
     * false is a refusal, never a default - see the class comment on why an MCP call is not itself
     * an approval.
     */
    private fun requireAthleteConfirmation(args: Map<String, Any?>, what: String) {
        // Absent and explicitly-false are reported differently on purpose: the first is a caller
        // that did not read the schema, the second is a caller saying the athlete did not agree.
        val raw = args["confirmedByAthlete"]
            ?: fail("INVALID_ARGUMENT", "confirmedByAthlete is required")
        val confirmed = when (raw) {
            is Boolean -> raw
            is String -> raw.equals("true", ignoreCase = true)
            else -> fail("INVALID_ARGUMENT", "confirmedByAthlete must be a boolean")
        }
        if (!confirmed) {
            fail("ATHLETE_CONFIRMATION_REQUIRED",
                "confirmedByAthlete must be true: $what requires the athlete's explicit agreement, " +
                    "which a tool call on its own does not establish. Show the draft to the athlete " +
                    "and call again only after they have agreed.")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun toMap(value: Any?): Map<String, Any?> =
        objectMapper.convertValue(value, LinkedHashMap::class.java) as Map<String, Any?>

    private fun constraints(args: Map<String, Any?>, userFeedback: String?) = SessionConstraints(
        availableMinutes = optionalIntArg(args, "availableMinutes", 1, MAX_AVAILABLE_MINUTES),
        environment = environmentArg(args),
        userFeedback = userFeedback,
        requestedGoal = textArg(args, "requestedGoal"),
        painOrFatigueFeedback = textArg(args, "painOrFatigueFeedback"),
    )

    private val constraintSchemaProperties: Map<String, Any> = mapOf(
        "availableMinutes" to mapOf("type" to "integer", "minimum" to 1, "maximum" to MAX_AVAILABLE_MINUTES,
            "description" to "Hard limit on how long the athlete can train, in minutes"),
        "environment" to mapOf("type" to "string", "enum" to TrainingEnvironment.entries.map { it.name },
            "description" to "Where the session must happen"),
        "painOrFatigueFeedback" to mapOf("type" to "string", "maxLength" to MAX_TEXT_LENGTH,
            "description" to "The athlete's own words about pain, niggles or fatigue. This is NOT " +
                "the injury report: a persistent injury is recorded through the injury API and " +
                "enforced by the safety gate regardless of what is written here."),
    )

    // ---- 1. create_workout_draft ----------------------------------------------------------------

    private fun createDraftTool() = McpSchema.Tool.builder()
        .name("create_workout_draft")
        .title("Create a workout draft")
        .description("Asks RunningAI's AI coach to design the session for a date (default today, " +
            "athlete timezone) and stores it as version 1 of a new DRAFT. This WRITES a draft row " +
            "and runs the coach on the RunningAI host; it does NOT approve anything and nothing is " +
            "sent to Intervals.icu or Garmin. If the coach is unavailable, times out or answers " +
            "outside its contract, the call fails with that reason and NO workout is invented. " +
            "The injury/pain safety gate is applied: while it allows REST only, a non-REST draft " +
            "is refused. A date whose draft is already approved cannot get a new one.")
        .inputSchema(schema(
            mapOf(
                "date" to mapOf("type" to "string", "format" to "date",
                    "description" to "Date (YYYY-MM-DD, athlete timezone); omit for today"),
                "userFeedback" to mapOf("type" to "string", "maxLength" to MAX_TEXT_LENGTH,
                    "description" to "Anything the athlete wants the coach to know for this session"),
                "requestedGoal" to mapOf("type" to "string", "maxLength" to MAX_TEXT_LENGTH,
                    "description" to "What the athlete wants out of this session, in their own words"),
            ) + constraintSchemaProperties,
        ))
        .annotations(writeAnnotations("Create a workout draft", idempotent = false))
        .build()

    private fun createWorkoutDraft(args: Map<String, Any?>, correlationId: String): Map<String, Any?> {
        requireOnlyKeys(args, "date", "availableMinutes", "environment", "userFeedback",
            "requestedGoal", "painOrFatigueFeedback")
        val date = dateArg(args, "date") ?: draftService.today()
        val draft = draftService.generate(date, constraints(args, textArg(args, "userFeedback")))
        log.info("MCP train: created draft id={} date={} cid={}", draft.id, date, correlationId)
        return linkedMapOf(
            "draft" to toMap(WorkoutDraftResponse.of(draft)),
            "nextStep" to "Show this draft to the athlete. Revise it with revise_workout_draft, or " +
                "once THEY have agreed to it, call approve_workout_draft with their confirmation.",
            "note" to "Stored as a draft only. Nothing has been approved, and nothing has been sent " +
                "to Intervals.icu or Garmin.",
        )
    }

    // ---- 2. get_workout_draft --------------------------------------------------------------------

    private fun getDraftTool() = McpSchema.Tool.builder()
        .name("get_workout_draft")
        .title("Read a workout draft")
        .description("Reads one stored workout draft by its id: the coach's assessment, the full " +
            "segment list with its intensity targets, the version and the lifecycle status " +
            "(DRAFT / APPROVED / SUPERSEDED). Read-only - nothing is stored, approved or published. " +
            "The `version` it returns is what approve_workout_draft requires as acknowledgedVersion.")
        .inputSchema(schema(
            mapOf("draftId" to mapOf("type" to "integer", "description" to "The draft's id")),
            required = listOf("draftId"),
        ))
        .annotations(readOnlyAnnotations("Read a workout draft"))
        .build()

    private fun getWorkoutDraft(args: Map<String, Any?>, correlationId: String): Map<String, Any?> {
        requireOnlyKeys(args, "draftId")
        val draft = draftService.get(longArg(args, "draftId"))
        return linkedMapOf(
            "draft" to toMap(WorkoutDraftResponse.of(draft)),
            "note" to "Read-only; nothing was stored, approved or published.",
        )
    }

    // ---- 3. revise_workout_draft -----------------------------------------------------------------

    private fun reviseDraftTool() = McpSchema.Tool.builder()
        .name("revise_workout_draft")
        .title("Revise a workout draft")
        .description("Hands the athlete's own words back to the AI coach, which designs the session " +
            "AGAIN and stores the result as a new version; the previous version becomes SUPERSEDED " +
            "and is kept for audit. This WRITES and runs the coach; it approves nothing and sends " +
            "nothing externally. An APPROVED draft is immutable and cannot be revised " +
            "(WORKOUT_DRAFT_APPROVED_IMMUTABLE) - the athlete's approved choice for a date never " +
            "changes underneath them. Spring never patches a duration or intensity mechanically: " +
            "the coach redesigns the session.")
        .inputSchema(schema(
            mapOf(
                "draftId" to mapOf("type" to "integer", "description" to "The draft version to revise"),
                "request" to mapOf("type" to "string", "maxLength" to MAX_TEXT_LENGTH,
                    "description" to "The athlete's change, in their own words (e.g. 'make it 30 minutes, my calf is tight')"),
            ) + constraintSchemaProperties,
            required = listOf("draftId", "request"),
        ))
        .annotations(writeAnnotations("Revise a workout draft", idempotent = false))
        .build()

    private fun reviseWorkoutDraft(args: Map<String, Any?>, correlationId: String): Map<String, Any?> {
        requireOnlyKeys(args, "draftId", "request", "availableMinutes", "environment", "painOrFatigueFeedback")
        val id = longArg(args, "draftId")
        val request = textArg(args, "request", required = true)!!
        val revised = draftService.revise(id, request, constraints(args, request))
        log.info("MCP train: revised draft id={} -> id={} version={} cid={}",
            id, revised.id, revised.version, correlationId)
        return linkedMapOf(
            "draft" to toMap(WorkoutDraftResponse.of(revised)),
            "supersededDraftId" to id,
            "nextStep" to "This is a NEW version with a new id. Show it to the athlete; approve this " +
                "id, not the superseded one.",
            "note" to "Stored as a draft only. Nothing has been approved or published.",
        )
    }

    // ---- 4. approve_workout_draft ----------------------------------------------------------------

    private fun approveDraftTool() = McpSchema.Tool.builder()
        .name("approve_workout_draft")
        .title("Approve a workout draft (athlete's decision)")
        .description("Records the ATHLETE'S explicit approval of one draft. A database lifecycle " +
            "step only: it never publishes and never contacts Intervals.icu or Garmin. After this " +
            "the draft is IMMUTABLE and is the athlete's committed choice for that date; at most " +
            "one draft per date can be approved, and approving the same draft again returns the " +
            "existing approval unchanged. The injury safety gate is re-checked here, independently " +
            "of what it said when the draft was generated. " +
            "Do NOT call this because you think the workout is good: it must be the athlete's own " +
            "decision. You must pass confirmedByAthlete=true and acknowledgedVersion, which is the " +
            "`version` of the draft you actually showed them - a mismatch is refused, so a draft " +
            "revised since they saw it can never be approved by accident.")
        .inputSchema(schema(
            mapOf(
                "draftId" to mapOf("type" to "integer", "description" to "The draft the athlete approved"),
                "confirmedByAthlete" to mapOf("type" to "boolean",
                    "description" to "True only when the athlete themselves agreed to THIS workout"),
                "acknowledgedVersion" to mapOf("type" to "integer",
                    "description" to "The `version` of the draft that was shown to the athlete"),
            ),
            required = listOf("draftId", "confirmedByAthlete", "acknowledgedVersion"),
        ))
        .annotations(writeAnnotations("Approve a workout draft (athlete's decision)", idempotent = true))
        .build()

    private fun approveWorkoutDraft(args: Map<String, Any?>, correlationId: String): Map<String, Any?> {
        requireOnlyKeys(args, "draftId", "confirmedByAthlete", "acknowledgedVersion")
        val id = longArg(args, "draftId")
        requireAthleteConfirmation(args, "approving a workout")
        val acknowledged = requiredIntArg(args, "acknowledgedVersion")

        // Read first: approval must be of the version the athlete actually saw. This also means a
        // caller that never read the draft cannot supply a correct value, so a blind approve fails.
        val current = draftService.get(id)
        if (current.version != acknowledged) {
            fail("DRAFT_VERSION_MISMATCH",
                "Draft $id is at version ${current.version}, but version $acknowledged was " +
                    "acknowledged. Re-read the draft, show the current version to the athlete, and " +
                    "approve only what they have actually seen.")
        }

        val approved = approvalService.approve(id)
        log.info("MCP train: approved draft id={} date={} approvalId={} cid={}",
            id, approved.draft.date, approved.approval.id, correlationId)
        return linkedMapOf(
            "approval" to toMap(WorkoutDraftApprovalResponse.of(approved)),
            "nextStep" to "Call preview_draft_publish to show the athlete exactly what would be sent " +
                "to Intervals.icu, then publish_approved_draft only if they still agree.",
            "note" to "Approved in the database only. NOTHING has been sent to Intervals.icu or " +
                "Garmin, and this draft can no longer be changed.",
        )
    }

    // ---- 5. preview_draft_publish ----------------------------------------------------------------

    private fun previewPublishTool() = McpSchema.Tool.builder()
        .name("preview_draft_publish")
        .title("Preview what publishing would send")
        .description("Shows exactly what a publish of this APPROVED draft would send to Intervals.icu " +
            "- the rendered Workout Builder text, the step count and the expected outcome (PUBLISH / " +
            "SKIPPED_REST_DAY / UNPUBLISHABLE, with the reasons). Read-only: it makes no Intervals " +
            "call, works even while publishing is switched off, and if this draft was already " +
            "published it also returns that stored publication. Use this to let the athlete see the " +
            "real workout before anything leaves RunningAI.")
        .inputSchema(schema(
            mapOf("draftId" to mapOf("type" to "integer", "description" to "The approved draft's id")),
            required = listOf("draftId"),
        ))
        .annotations(readOnlyAnnotations("Preview what publishing would send"))
        .build()

    private fun previewDraftPublish(args: Map<String, Any?>, correlationId: String): Map<String, Any?> {
        requireOnlyKeys(args, "draftId")
        val preview = publishService.preview(longArg(args, "draftId"))
        return linkedMapOf(
            "preview" to toMap(DraftPublishPreviewResponse.of(preview)),
            "note" to "Read-only. No Intervals.icu call was made and nothing was published.",
        )
    }

    // ---- 6. publish_approved_draft ---------------------------------------------------------------

    private fun publishTool() = McpSchema.Tool.builder()
        .name("publish_approved_draft")
        .title("Publish an approved draft to Intervals.icu")
        .description("Sends an APPROVED draft to the athlete's Intervals.icu calendar (a rest day is " +
            "recorded as SKIPPED_REST_DAY and sends nothing). This is the ONLY tool that writes to " +
            "an external system. Requires its own OAuth scope. " +
            "Guarantees: an unapproved draft is refused; the published workout is the approved one, " +
            "which has been immutable since approval; publishing the same draft twice returns the " +
            "stored first result without calling Intervals again; concurrent requests for one draft " +
            "are rejected rather than racing; a failed publish is NOT recorded, so the draft stays " +
            "approved and the call can simply be repeated; and the injury safety gate is re-checked " +
            "immediately before anything is sent. If the outcome is uncertain, re-run " +
            "preview_draft_publish and read its `publication` field instead of publishing again. " +
            "You must pass confirmedByAthlete=true and acknowledgedApprovalId from the approval or " +
            "preview - a tool call is not itself the athlete's consent to publish.")
        .inputSchema(schema(
            mapOf(
                "draftId" to mapOf("type" to "integer", "description" to "The approved draft to publish"),
                "confirmedByAthlete" to mapOf("type" to "boolean",
                    "description" to "True only when the athlete themselves agreed to send this to their calendar"),
                "acknowledgedApprovalId" to mapOf("type" to "integer",
                    "description" to "The `approvalId` from approve_workout_draft or preview_draft_publish"),
            ),
            required = listOf("draftId", "confirmedByAthlete", "acknowledgedApprovalId"),
        ))
        .annotations(writeAnnotations("Publish an approved draft to Intervals.icu",
            idempotent = true, openWorld = true))
        .build()

    private fun publishApprovedDraft(args: Map<String, Any?>, correlationId: String): Map<String, Any?> {
        requireOnlyKeys(args, "draftId", "confirmedByAthlete", "acknowledgedApprovalId")
        val id = longArg(args, "draftId")
        requireAthleteConfirmation(args, "publishing a workout to the athlete's calendar")
        val acknowledgedApprovalId = longArg(args, "acknowledgedApprovalId")

        // Read-only check first, so a wrong acknowledgement is refused BEFORE the publish path is
        // entered at all - and so the id cannot be guessed by a caller that never saw the approval.
        val preview = publishService.preview(id)
        val actualApprovalId = preview.approved.approval.id
        if (actualApprovalId != acknowledgedApprovalId) {
            fail("APPROVAL_MISMATCH",
                "Draft $id is approved under approvalId $actualApprovalId, but " +
                    "$acknowledgedApprovalId was acknowledged. Re-read the approval with " +
                    "preview_draft_publish and publish only what the athlete has actually seen.")
        }

        val outcome = publishService.publish(id)
        log.info("MCP train: published draft id={} outcome={} alreadyPublished={} cid={}",
            id, outcome.publication.outcome, outcome.alreadyPublished, correlationId)
        return linkedMapOf(
            "publication" to toMap(DraftPublishResponse.of(outcome)),
            "note" to if (outcome.alreadyPublished) {
                "This draft was already published; the stored result was returned and Intervals.icu " +
                    "was NOT called again."
            } else {
                "Published. Re-running this tool returns this same stored result rather than " +
                    "sending a second copy."
            },
        )
    }

    private companion object {
        const val MAX_TEXT_LENGTH = 2000
        const val MAX_AVAILABLE_MINUTES = 600
    }
}
