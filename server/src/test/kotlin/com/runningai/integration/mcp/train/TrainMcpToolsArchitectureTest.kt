package com.runningai.integration.mcp.train

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Structural guarantee of Phase 7F-1, the mirror image of `McpReadToolsArchitectureTest`: these
 * tools are allowed to write, but ONLY by going through the lifecycle services the REST API
 * already goes through. The point is that no safety rule can be bypassed by reaching past them -
 * if the adapter could call the renderer, the publisher or a repository itself, the approval
 * requirement, the injury gate, the publishing master switch and the single-flight guard would all
 * become optional, and a future edit could quietly make them so.
 *
 * Enforced the same two ways as the read-tool test: the sources are scanned, and the beans'
 * constructor dependencies are asserted to contain nothing but the three services.
 */
class TrainMcpToolsArchitectureTest {

    private val sources: List<Path> =
        Files.walk(Path.of("src/main/kotlin/com/runningai/integration/mcp/train")).use { stream ->
            stream.filter { it.toString().endsWith(".kt") }.toList()
        }

    private fun codeOf(path: Path): String = path.readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("(?m)//.*$"), "")

    @Test
    fun `the train-tool sources exist and were actually scanned`() {
        assertThat(sources.map { it.name }).contains("TrainMcpTools.kt", "McpTrainToolConfig.kt")
    }

    @Test
    fun `no train-tool source reaches past the lifecycle services to a transport or a renderer`() {
        val forbidden = listOf(
            // Intervals write path: only ApprovedWorkoutDraftPublishService may walk it.
            "IntervalsWorkoutPublisher", "IntervalsWorkoutClient", "HttpIntervalsWorkoutClient",
            "IntervalsWorkoutRenderer", "WorkoutDraftStructuredWorkoutMapper",
            "ApprovedWorkoutDraftPublishabilityValidator",
            // The legacy deterministic path re-prescribes the workout: an approved draft must never
            // go near it, or what is published would not be what the athlete approved.
            "WorkoutPublishApplicationService", "WorkoutIntensityTargetService", "PublishWorkoutMcpTool",
            "publish_workout",
            // The coach itself: drafts are created through WorkoutDraftService, which applies the
            // safety gate and persists - never by calling the coach directly.
            "ClaudeAiCoach", "AiCoachProvider",
            // Persistence: the services own every write.
            "Repository", "WorkoutDraftStore", "WorkoutDraftPublicationStore", "EntityManager",
            // Bypassing the safety gate's callers by evaluating it here instead.
            "WorkoutSafetyGateService",
            // Garmin / Intervals sync triggers and outbound HTTP of any kind.
            "GarminActivitySource", "GarminSyncOperationService", "CoachDataRefreshService",
            "IntervalsReadClient", "HistoricalBackfillService",
            "RestClient", "RestTemplate", "WebClient", "HttpClient",
        )
        val offenders = sources.flatMap { path ->
            val code = codeOf(path)
            forbidden.filter { code.contains(it) }.map { "${path.name} references $it" }
        }

        assertThat(offenders)
            .withFailMessage("the training MCP tools must write only through the lifecycle services: %s", offenders)
            .isEmpty()
    }

    @Test
    fun `no train-tool source persists or publishes anything on its own`() {
        val forbidden = listOf(Regex("\\.save\\("), Regex("\\.saveAll\\("), Regex("\\.delete"),
            Regex("\\.flush\\("), Regex("\\.render\\("), Regex("\\.recordPublished\\("),
            Regex("\\.recordRestDay\\("))
        val offenders = sources.flatMap { path ->
            val code = codeOf(path)
            forbidden.filter { it.containsMatchIn(code) }.map { "${path.name} matches ${it.pattern}" }
        }
        assertThat(offenders).isEmpty()
    }

    @Test
    fun `the committing tools demand the athlete's confirmation, and only they do`() {
        val code = codeOf(sources.first { it.name == "TrainMcpTools.kt" })
        // Both committing steps must go through the one confirmation helper. The pattern includes
        // the opening quote of the second argument so it matches the two CALL sites and not the
        // function's own declaration.
        assertThat(Regex("requireAthleteConfirmation\\(args, \"").findAll(code).count())
            .`as`("approve and publish must each require an explicit confirmation")
            .isEqualTo(2)
        // ...and each must also verify an echo the caller can only have got by reading first.
        assertThat(code).contains("DRAFT_VERSION_MISMATCH").contains("APPROVAL_MISMATCH")
    }

    @Test
    fun `nothing substitutes a workout when the coach fails`() {
        val code = codeOf(sources.first { it.name == "TrainMcpTools.kt" })
        // An AiCoachException may only ever become an error result. If it were ever caught and
        // turned into a draft, that draft would be the adapter's invention, not the coach's.
        assertThat(code).doesNotContain("catch (e: AiCoachException)")
        assertThat(code).contains("AI_COACH_\${e.reason.name}")
    }

    @Test
    fun `the train-tool beans depend on nothing but the three lifecycle services and Jackson`() {
        val allowed = setOf(
            "com.runningai.coach.WorkoutDraftService",
            "com.runningai.coach.WorkoutDraftApprovalService",
            "com.runningai.draftpublish.ApprovedWorkoutDraftPublishService",
            "com.fasterxml.jackson.databind.ObjectMapper",
        )
        val constructorParams = TrainMcpTools::class.java.constructors
            .flatMap { it.parameterTypes.toList() }.map { it.name }
        val beanMethodParams = McpTrainToolConfig::class.java.methods
            .filter { it.name == "trainMcpTools" }
            .flatMap { it.parameterTypes.toList() }.map { it.name }

        assertThat(constructorParams).isNotEmpty.allMatch { it in allowed }
        assertThat(beanMethodParams).isNotEmpty.allMatch { it in allowed }
    }
}
