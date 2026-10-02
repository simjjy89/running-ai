package com.runningai.draftpublish

import com.runningai.coach.WorkoutDraftApprovalService
import com.runningai.coach.WorkoutDraftService
import com.runningai.coach.claude.ClaudeAiCoach
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Structural guarantees of Phase 6G, independent of any switch:
 *  - the approved-draft path never re-prescribes (no legacy deterministic pipeline type);
 *  - the AI coach, the draft service and approval have no route to the publisher;
 *  - nothing triggers a draft publish automatically (no scheduler, no startup hook, no MCP tool);
 *  - the Claude CLI still runs with tools and MCP disabled.
 */
class DraftPublishArchitectureTest {

    private fun sources(dir: String): List<Path> = Files.walk(Path.of(dir)).use { s ->
        s.filter { it.toString().endsWith(".kt") || it.toString().endsWith(".java") }.toList()
    }

    private fun codeOf(path: Path): String = path.readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("(?m)//.*$"), "")

    private val draftPublishSources = sources("src/main/kotlin/com/runningai/draftpublish")
    private val coachSources = sources("src/main/kotlin/com/runningai/coach")
    private val mcpSources = sources("src/main/java/com/runningai/integration/mcp")

    private fun offenders(files: List<Path>, patterns: List<String>): List<String> = files.flatMap { path ->
        val code = codeOf(path)
        patterns.filter { Regex(it).containsMatchIn(code) }.map { "${path.name} matches $it" }
    }

    @Test
    fun `the sources were actually scanned`() {
        assertThat(draftPublishSources.map { it.name }).contains("ApprovedWorkoutDraftPublishService.kt")
        assertThat(mcpSources).isNotEmpty
    }

    @Test
    fun `the approved-draft path never uses the legacy deterministic prescription pipeline`() {
        val forbidden = listOf(
            "\\bWorkoutPublishApplicationService\\b",
            "\\bWorkoutIntensityTargetService\\b",
            "\\btargetedPrescribe\\b",
            "\\bTargetedWorkoutPrescription\\b",
            "\\bStructuredWorkoutMapper\\b",          // the legacy mapper; WorkoutDraftStructuredWorkoutMapper does not match
            "\\bWorkoutRecommendationService\\b",
            "\\bWorkoutPrescriptionService\\b",
        )

        assertThat(offenders(draftPublishSources, forbidden)).isEmpty()
    }

    @Test
    fun `the approved-draft path writes only through the existing publisher, never the raw client or Garmin`() {
        val forbidden = listOf(
            "\\bIntervalsWorkoutClient\\b",
            "\\bHttpIntervalsWorkoutClient\\b",
            "\\bRestClient\\b",
            "\\bRestTemplate\\b",
            "\\bWebClient\\b",
            "import com\\.runningai\\.integration\\.garmin",
        )

        assertThat(offenders(draftPublishSources, forbidden)).isEmpty()
        val deps = ApprovedWorkoutDraftPublishService::class.java.constructors.single().parameterTypes.map { it.simpleName }
        assertThat(deps).contains("IntervalsWorkoutPublisher", "IntervalsWorkoutRenderer", "WorkoutDraftStructuredWorkoutMapper")
        assertThat(deps).doesNotContain(
            "WorkoutPublishApplicationService", "WorkoutIntensityTargetService", "StructuredWorkoutMapper",
            "IntervalsWorkoutClient",
        )
    }

    @Test
    fun `nothing publishes an approved draft automatically`() {
        val triggers = listOf("@Scheduled", "@EventListener", "ApplicationRunner", "CommandLineRunner", "@PostConstruct", "@McpTool", "@Tool\\b")

        assertThat(offenders(draftPublishSources, triggers)).isEmpty()
    }

    @Test
    fun `the coach, the draft service and approval have no route to the publish gateway`() {
        val forbidden = listOf(
            "import com\\.runningai\\.draftpublish",
            "\\bApprovedWorkoutDraftPublishService\\b",
            "\\bIntervalsWorkoutPublisher\\b",
            "\\bIntervalsWorkoutRenderer\\b",
        )
        assertThat(offenders(coachSources, forbidden)).isEmpty()

        val deps = listOf(ClaudeAiCoach::class.java, WorkoutDraftService::class.java, WorkoutDraftApprovalService::class.java)
            .flatMap { it.constructors.toList() }
            .flatMap { it.parameterTypes.toList() }
            .map { it.name }
        assertThat(deps).noneMatch {
            it.startsWith("com.runningai.draftpublish") || it.startsWith("com.runningai.integration")
        }
    }

    @Test
    fun `the MCP adapter gained no approve or draft-publish capability`() {
        val forbidden = listOf(
            "\\bWorkoutDraft", "\\bApprovedWorkoutDraftPublishService\\b", "draftpublish", "approve",
            "\\bWorkoutDraftApprovalService\\b",
        )

        assertThat(offenders(mcpSources, forbidden)).isEmpty()
    }

    @Test
    fun `the Claude CLI still runs with every tool and MCP server disabled`() {
        val cli = codeOf(Path.of("src/main/kotlin/com/runningai/coach/claude/ClaudeCliClient.kt"))

        assertThat(cli).contains("\"--tools\", \"\"", "\"--strict-mcp-config\"", "\"--setting-sources\", \"\"")
    }
}
