package com.runningai.coach

import com.runningai.coach.claude.ClaudeAiCoach
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * The single most important safety property of Phase 6E: **designing a draft can never publish.**
 *
 * Enforced two ways, because either alone could be worked around: the coach/draft sources are
 * scanned for any reference to a publishing, Intervals or Garmin-write type, and the runtime
 * constructor dependencies of the coach and draft beans are asserted to contain none of them.
 *
 * This must hold regardless of `running-ai.workout-publishing.enabled`, which is exactly why it is
 * a structural test rather than a configuration one.
 */
class CoachArchitectureTest {

    private val coachMainSources: List<Path> =
        Files.walk(Path.of("src/main/kotlin/com/runningai/coach")).use { stream ->
            stream.filter { it.toString().endsWith(".kt") }.toList()
        }

    /**
     * Source with comments removed. Scanning raw text would flag a comment that merely *explains*
     * why something is unreachable (for example the note in `ClaudeCliClient` about
     * `--strict-mcp-config` making the `publish_workout` MCP tool unreachable), which is
     * documentation worth keeping, not a dependency. Only real code is checked.
     */
    private fun codeOf(path: Path): String = path.readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("(?m)//.*$"), "")

    /**
     * Types and APIs that would turn a draft into a real write. The Intervals renderer and the
     * structured-workout mapper are deliberately NOT on this list: they only produce text and could
     * legitimately be reused later for a preview. Anything that performs or triggers a write is.
     */
    private val forbiddenReferences = listOf(
        "WorkoutPublishApplicationService",
        "IntervalsWorkoutPublisher",
        "IntervalsWorkoutClient",
        "HttpIntervalsWorkoutClient",
        "IntervalsEventDraft",
        "IntervalsPublishResult",
        "PublishWorkoutMcpTool",
        "GarminActivitySource",
        "GarminLactateThresholdSource",
        "GarminSyncOperationService",
        "publish_workout",
    )

    @Test
    fun `coach sources exist and were actually scanned`() {
        assertThat(coachMainSources).isNotEmpty
        assertThat(coachMainSources.map { it.name }).contains("WorkoutDraftService.kt", "AiCoach.kt")
    }

    @Test
    fun `no coach or draft source references a publishing, Intervals or Garmin-write type`() {
        val offenders = mutableListOf<String>()
        coachMainSources.forEach { path ->
            val code = codeOf(path)
            forbiddenReferences.forEach { forbidden ->
                if (code.contains(forbidden)) offenders += "${path.name} references $forbidden"
            }
        }

        assertThat(offenders)
            .withFailMessage("AI coach / draft code must never be able to publish, but: %s", offenders)
            .isEmpty()
    }

    @Test
    fun `no coach or draft source imports the intervals or garmin packages`() {
        val offenders = coachMainSources.filter { path ->
            codeOf(path).lineSequence().any {
                val line = it.trim()
                line.startsWith("import com.runningai.integration.intervals") ||
                    line.startsWith("import com.runningai.integration.garmin") ||
                    line.startsWith("import com.runningai.integration.mcp")
            }
        }

        assertThat(offenders.map { it.name })
            .withFailMessage("AI coach / draft code must not import the publishing integrations: %s", offenders)
            .isEmpty()
    }

    @Test
    fun `no coach or draft source performs an HTTP write`() {
        // @PostMapping / @PutMapping are inbound REST, not outbound writes, so they are not markers.
        val httpWriteMarkers = listOf("RestClient", "RestTemplate", "WebClient", "HttpClient", ".post(", ".put(")
        val offenders = mutableListOf<String>()
        coachMainSources.forEach { path ->
            val code = codeOf(path)
            httpWriteMarkers.forEach { marker ->
                if (code.contains(marker)) offenders += "${path.name} contains $marker"
            }
        }

        assertThat(offenders)
            .withFailMessage("AI coach / draft code must not make outbound HTTP calls: %s", offenders)
            .isEmpty()
    }

    @Test
    fun `the coach and draft beans declare no publishing dependency`() {
        val constructors = listOf(
            ClaudeAiCoach::class.java,
            WorkoutDraftService::class.java,
            WorkoutDraftStore::class.java,
            WorkoutDraftController::class.java,
            TrainingContextBuilder::class.java,
            WorkoutDraftValidator::class.java,
        ).flatMap { it.constructors.toList() }

        val dependencyNames = constructors
            .flatMap { it.parameterTypes.toList() }
            .map { it.name }

        assertThat(dependencyNames).noneMatch { name ->
            name.startsWith("com.runningai.integration.intervals") ||
                name.startsWith("com.runningai.integration.mcp")
        }
        // The coach reads Garmin-derived data only through athlete/training services, never through
        // a Garmin transport type that could also write.
        assertThat(dependencyNames).noneMatch { it.startsWith("com.runningai.integration.garmin") }
    }

    @Test
    fun `the AiCoach contract exposes no vendor type`() {
        val signatures = AiCoach::class.java.methods.flatMap {
            it.parameterTypes.toList() + it.returnType
        }.map { it.name }

        assertThat(signatures).noneMatch { it.contains("Claude", ignoreCase = true) }
        assertThat(signatures).noneMatch { it.contains("Cli", ignoreCase = true) }
        assertThat(signatures).allMatch {
            it.startsWith("com.runningai.coach.") || it.startsWith("java.") || it.startsWith("kotlin.")
        }
    }
}
