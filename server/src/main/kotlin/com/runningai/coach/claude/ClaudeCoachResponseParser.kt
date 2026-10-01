package com.runningai.coach.claude

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.runningai.coach.AiCoachException
import com.runningai.coach.CoachAssessment
import com.runningai.coach.CoachProvider
import com.runningai.coach.WorkoutDraft
import com.runningai.coach.WorkoutDraftSegment
import com.runningai.training.IntensityClass
import com.runningai.training.SegmentType
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * Turns one Claude CLI invocation's stdout into a [WorkoutDraft].
 *
 * Two JSON layers are involved and are parsed separately on purpose:
 *  1. the **outer** CLI envelope (`is_error`, `subtype`, `result`, `modelUsage`, ...), which is a
 *     property of the Claude Code CLI; and
 *  2. the **inner** coach JSON the model wrote, which is the provider-neutral contract.
 *
 * Keeping them apart means the coach contract is not coupled to the CLI's envelope, and a change in
 * either layer produces a precise error rather than a confusing one.
 *
 * Strictness is the point: an unknown or missing required field, an unparseable enum, or a
 * non-numeric number is an explicit failure. Nothing is ever silently defaulted, because a quietly
 * defaulted field would mean handing the athlete a workout the coach did not actually prescribe.
 * This class performs no network or process work and is independent of [ClaudeAiCoach].
 */
@Component
class ClaudeCoachResponseParser(private val objectMapper: ObjectMapper) {

    fun parse(stdout: String, expectedDate: LocalDate, model: String?, version: Int): WorkoutDraft {
        val envelope = readEnvelope(stdout)
        val inner = extractInnerJson(envelope)
        return toDraft(inner, expectedDate, model ?: envelopeModel(envelope), version)
    }

    private fun readEnvelope(stdout: String): JsonNode {
        val node = try {
            objectMapper.readTree(stdout)
        } catch (e: Exception) {
            throw AiCoachException(
                AiCoachException.Reason.INVALID_RESPONSE,
                "Claude CLI output was not valid JSON",
                e,
            )
        }
        if (node == null || !node.isObject) {
            throw AiCoachException(
                AiCoachException.Reason.INVALID_RESPONSE,
                "Claude CLI output was not a JSON object",
            )
        }
        if (node.path("is_error").asBoolean(false)) {
            val subtype = node.path("subtype").asText("unknown")
            throw AiCoachException(
                AiCoachException.Reason.PROVIDER_ERROR,
                "Claude CLI reported an error (subtype=$subtype)",
            )
        }
        return node
    }

    /**
     * The coach JSON itself. `structured_output` is preferred when the CLI produced one (it is
     * already parsed), otherwise the model's `result` text is parsed. A markdown fence is stripped
     * defensively even though the contract forbids it, because a fenced-but-otherwise-correct
     * answer is a formatting slip rather than a wrong workout.
     */
    private fun extractInnerJson(envelope: JsonNode): JsonNode {
        val structured = envelope.get("structured_output")
        if (structured != null && structured.isObject) {
            return structured
        }
        val result = envelope.get("result")
        if (result == null || !result.isTextual || result.asText().isBlank()) {
            throw AiCoachException(
                AiCoachException.Reason.INVALID_RESPONSE,
                "Claude CLI response contained no result text",
            )
        }
        val text = stripFence(result.asText().trim())
        val inner = try {
            objectMapper.readTree(text)
        } catch (e: Exception) {
            throw AiCoachException(
                AiCoachException.Reason.INVALID_RESPONSE,
                "The coach response was not valid JSON",
                e,
            )
        }
        if (inner == null || !inner.isObject) {
            throw AiCoachException(
                AiCoachException.Reason.INVALID_RESPONSE,
                "The coach response was not a JSON object",
            )
        }
        return inner
    }

    private fun stripFence(text: String): String {
        if (!text.startsWith("```")) return text
        return text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    }

    private fun envelopeModel(envelope: JsonNode): String? =
        envelope.path("modelUsage").fieldNames().asSequence().firstOrNull()

    private fun toDraft(inner: JsonNode, date: LocalDate, model: String?, version: Int): WorkoutDraft {
        val assessmentNode = requireObject(inner, "assessment")
        val workoutNode = requireObject(inner, "workout")

        val assessment = CoachAssessment(
            recoveryAssessment = requireText(assessmentNode, "recovery"),
            loadAssessment = requireText(assessmentNode, "loadTrend"),
            selectedWorkoutType = requireText(assessmentNode, "selectedWorkoutType"),
            rationale = requireText(assessmentNode, "rationale"),
            warnings = assessmentNode.path("warnings")
                .takeIf { it.isArray }
                ?.mapNotNull { it.takeIf { n -> n.isTextual }?.asText()?.takeIf(String::isNotBlank) }
                ?: emptyList(),
        )

        val segmentsNode = workoutNode.get("segments")
        if (segmentsNode == null || !segmentsNode.isArray) {
            throw invalid("workout.segments is missing or is not an array")
        }
        val segments = segmentsNode.mapIndexed { i, n -> toSegment(n, i) }

        return WorkoutDraft(
            version = version,
            date = date,
            title = requireText(workoutNode, "title"),
            workoutType = assessment.selectedWorkoutType,
            totalDurationMinutes = requireInt(workoutNode, "totalDurationMinutes"),
            assessment = assessment,
            segments = segments,
            provider = CoachProvider.CLAUDE,
            model = model,
        )
    }

    private fun toSegment(node: JsonNode, index: Int): WorkoutDraftSegment {
        if (!node.isObject) throw invalid("workout.segments[$index] is not an object")
        val at = "workout.segments[$index]"
        return WorkoutDraftSegment(
            type = enumValue<SegmentType>(requireText(node, "type", at), at, "type"),
            durationMinutes = requireInt(node, "durationMinutes", at),
            intensity = enumValue<IntensityClass>(requireText(node, "intensity", at), at, "intensity"),
            description = optionalText(node, "description"),
            paceSecondsPerKmFast = optionalInt(node, "paceSecondsPerKmFast", at),
            paceSecondsPerKmSlow = optionalInt(node, "paceSecondsPerKmSlow", at),
            heartRateBpmMin = optionalInt(node, "heartRateBpmMin", at),
            heartRateBpmMax = optionalInt(node, "heartRateBpmMax", at),
            treadmillSpeedKphMin = optionalDouble(node, "treadmillSpeedKphMin", at),
            treadmillSpeedKphMax = optionalDouble(node, "treadmillSpeedKphMax", at),
            inclinePercentMin = optionalDouble(node, "inclinePercentMin", at),
            inclinePercentMax = optionalDouble(node, "inclinePercentMax", at),
            repetitions = optionalInt(node, "repetitions", at),
            recoveryDurationMinutes = optionalInt(node, "recoveryDurationMinutes", at),
        )
    }

    private inline fun <reified E : Enum<E>> enumValue(raw: String, at: String, field: String): E =
        enumValues<E>().firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: throw invalid(
                "$at.$field '$raw' is not one of ${enumValues<E>().joinToString("|") { it.name }}",
            )

    private fun requireObject(node: JsonNode, field: String): JsonNode =
        node.get(field)?.takeIf { it.isObject } ?: throw invalid("$field is missing or is not an object")

    private fun requireText(node: JsonNode, field: String, at: String = ""): String {
        val n = node.get(field)
        if (n == null || !n.isTextual || n.asText().isBlank()) {
            throw invalid("${prefix(at)}$field is missing or is not a non-empty string")
        }
        return n.asText().trim()
    }

    private fun requireInt(node: JsonNode, field: String, at: String = ""): Int {
        val n = node.get(field)
        if (n == null || !n.isIntegralNumber) {
            throw invalid("${prefix(at)}$field is missing or is not an integer")
        }
        return n.asInt()
    }

    private fun optionalText(node: JsonNode, field: String): String? =
        node.get(field)?.takeIf { it.isTextual && it.asText().isNotBlank() }?.asText()?.trim()

    private fun optionalInt(node: JsonNode, field: String, at: String): Int? {
        val n = node.get(field) ?: return null
        if (n.isNull) return null
        if (!n.isIntegralNumber) throw invalid("${prefix(at)}$field is not an integer")
        return n.asInt()
    }

    private fun optionalDouble(node: JsonNode, field: String, at: String): Double? {
        val n = node.get(field) ?: return null
        if (n.isNull) return null
        if (!n.isNumber) throw invalid("${prefix(at)}$field is not a number")
        val value = n.asDouble()
        if (!value.isFinite()) throw invalid("${prefix(at)}$field is not a finite number")
        return value
    }

    private fun prefix(at: String) = if (at.isBlank()) "" else "$at."

    private fun invalid(message: String) =
        AiCoachException(AiCoachException.Reason.INVALID_RESPONSE, message)
}
