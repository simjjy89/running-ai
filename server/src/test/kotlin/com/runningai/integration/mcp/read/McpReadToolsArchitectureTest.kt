package com.runningai.integration.mcp.read

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Structural guarantee of Phase 7C-2a: the read-only MCP tools CANNOT write, by construction -
 * not merely by intent. Enforced the same two ways as `CoachArchitectureTest`: the sources are
 * scanned for any reference to a write/trigger type, and the beans' constructor dependencies are
 * asserted to contain none of them.
 */
class McpReadToolsArchitectureTest {

    private val sources: List<Path> =
        Files.walk(Path.of("src/main/kotlin/com/runningai/integration/mcp/read")).use { stream ->
            stream.filter { it.toString().endsWith(".kt") }.toList()
        }

    private fun codeOf(path: Path): String = path.readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("(?m)//.*$"), "")

    @Test
    fun `the read-tool sources exist and were actually scanned`() {
        assertThat(sources.map { it.name }).contains("ReadMcpTools.kt", "McpReadToolConfig.kt")
    }

    @Test
    fun `no read-tool source references a publishing, draft, sync or Garmin-write type`() {
        val forbidden = listOf(
            // publishing / Intervals write
            "WorkoutPublishApplicationService", "IntervalsWorkoutPublisher", "IntervalsWorkoutClient",
            "HttpIntervalsWorkoutClient", "IntervalsWorkoutRenderer", "PublishWorkoutMcpTool", "publish_workout",
            "ApprovedWorkoutDraftPublishService", "draftpublish",
            // AI-coach draft creation (a write: it stores a draft and calls the CLI)
            "WorkoutDraftService", "AiCoach", "ClaudeAiCoach",
            // Garmin / Intervals transports and sync triggers
            "GarminActivitySource", "GarminRecoveryClient", "GarminRecoverySyncService",
            "GarminSyncOperationService", "GarminActivityDetailSource", "GarminLactateThresholdSource",
            "GarminAthleteProfileSyncService", "CoachDataRefreshService", "HistoricalBackfillService",
            "IntervalsReadClient", "HttpIntervalsReadClient", "IntervalsEnrichmentService",
            // outbound HTTP of any kind
            "RestClient", "RestTemplate", "WebClient", "HttpClient",
        )
        val offenders = sources.flatMap { path ->
            val code = codeOf(path)
            forbidden.filter { code.contains(it) }.map { "${path.name} references $it" }
        }

        assertThat(offenders)
            .withFailMessage("read-only MCP tools must have no route to a write or a sync: %s", offenders)
            .isEmpty()
    }

    @Test
    fun `no read-tool source can trigger a re-analysis or persist anything`() {
        // analyse( is the recompute entry point; save(/delete( would be repository writes. The read
        // tools may only call find/read methods on the services they hold.
        val forbidden = listOf(Regex("\\.analyse\\("), Regex("\\.save\\("), Regex("\\.saveAll\\("),
            Regex("\\.delete"), Regex("\\.upsert"), Regex("\\.replace\\("), Regex("\\.record\\("))
        val offenders = sources.flatMap { path ->
            val code = codeOf(path)
            forbidden.filter { it.containsMatchIn(code) }.map { "${path.name} matches ${it.pattern}" }
        }

        assertThat(offenders).isEmpty()
    }

    @Test
    fun `the read-tool beans declare no dependency from a publishing, draft-publish or transport package`() {
        val dependencyNames = listOf(ReadMcpTools::class.java, McpReadToolConfig::class.java)
            .flatMap { it.constructors.toList() }
            .flatMap { it.parameterTypes.toList() }
            .map { it.name }
        // ReadMcpTools' bean method parameters are the real injection points.
        val beanMethodParams = McpReadToolConfig::class.java.methods
            .flatMap { it.parameterTypes.toList() }
            .map { it.name }

        assertThat(dependencyNames + beanMethodParams).noneMatch { name ->
            name.startsWith("com.runningai.integration.intervals") ||
                name.startsWith("com.runningai.integration.garmin") ||
                name.startsWith("com.runningai.draftpublish") ||
                name.startsWith("com.runningai.coachrefresh") ||
                name.startsWith("com.runningai.backfill")
        }
    }
}
