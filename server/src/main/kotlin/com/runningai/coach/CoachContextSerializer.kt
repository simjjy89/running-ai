package com.runningai.coach

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Instant

/** The built context, serialized, is larger than the configured hard guard. Nothing is sent to the coach. */
class TrainingContextTooLargeException(val version: ContextVersion, val actualBytes: Int, val maxBytes: Int) :
    RuntimeException("$version context snapshot is $actualBytes bytes, exceeding the $maxBytes byte limit")

/** What a draft (or a preview) actually saw: the exact bytes, their hash, and when they were built. */
data class ContextSnapshot(
    val version: ContextVersion,
    val json: String,
    val sha256: String,
    val builtAt: Instant,
)

/**
 * Canonical, deterministic serialization of a [CoachTrainingContext] (Phase 6H-7): fixed map-key
 * order, ISO-8601 dates, no timestamps — the same context always serializes to the same bytes, which
 * is what makes [ContextSnapshot.sha256] meaningful for audit/diff. Enforces the size guard (§40):
 * exceeding `running-ai.coach.context-v2.max-snapshot-bytes` is [TrainingContextTooLargeException],
 * raised **before** any coach call, never a silent truncation.
 */
@Component
class CoachContextSerializer(private val properties: TrainingContextV2Properties) {

    private val mapper: ObjectMapper = ObjectMapper()
        .registerKotlinModule()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)

    fun snapshot(context: CoachTrainingContext, builtAt: Instant): ContextSnapshot {
        val version = versionOf(context)
        val json = mapper.writeValueAsString(context)
        val bytes = json.toByteArray(Charsets.UTF_8)
        if (bytes.size > properties.maxSnapshotBytes) {
            throw TrainingContextTooLargeException(version, bytes.size, properties.maxSnapshotBytes)
        }
        return ContextSnapshot(version, json, sha256(bytes), builtAt)
    }

    private fun versionOf(context: CoachTrainingContext): ContextVersion = when (context) {
        is TrainingContextV2 -> ContextVersion.V2
        is TrainingContext -> ContextVersion.V1
        else -> error("Unsupported CoachTrainingContext implementation: ${context.javaClass.name}")
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
