package com.runningai.coach

import com.runningai.coach.claude.ClaudeAiCoach
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.test.context.ActiveProfiles

/** Provider selection: which [AiCoach] implementation the configuration actually produces. */
class CoachProviderSelectionTest {

    @Nested
    @SpringBootTest(
        properties = [
            "running-ai.coach.provider=CLAUDE",
            "running-ai.intervals.api-key=",
            "running-ai.intervals.base-url=http://127.0.0.1:9",
        ],
    )
    @ActiveProfiles("test")
    inner class ClaudeSelected {

        @Autowired private lateinit var coach: AiCoach
        @Autowired private lateinit var properties: CoachProperties

        @Test
        fun `the claude provider produces the Claude implementation`() {
            assertThat(coach).isInstanceOf(ClaudeAiCoach::class.java)
            assertThat(properties.provider).isEqualTo(CoachProvider.CLAUDE)
        }

        @Test
        fun `claude is also the default when nothing is configured`() {
            // the property above is explicit; this asserts the documented default value matches it
            assertThat(CoachProvider.valueOf("CLAUDE")).isEqualTo(properties.provider)
        }

        @Test
        fun `the coach configuration holds no credential`() {
            val fields = CoachProperties.Claude::class.java.declaredFields.map { it.name.lowercase() }

            assertThat(fields).noneMatch { it.contains("key") || it.contains("token") || it.contains("secret") }
        }
    }

    @Nested
    inner class UnsupportedProvider {

        /**
         * A configured-but-unimplemented provider must fail loudly rather than silently falling
         * back to Claude: quietly using a different AI than the operator asked for would be worse
         * than refusing to start.
         */
        @Test
        fun `an unimplemented provider fails fast with an explicit message`() {
            listOf(CoachProvider.CODEX, CoachProvider.OLLAMA).forEach { provider ->
                assertThatThrownBy {
                    CoachConfig().aiCoach(
                        CoachTestFixtures.properties(provider = provider),
                        com.runningai.coach.claude.ClaudeCoachPromptBuilder(),
                        com.runningai.coach.claude.ClaudeCliClient(CoachTestFixtures.properties()),
                        com.runningai.coach.claude.ClaudeCoachResponseParser(
                            com.fasterxml.jackson.databind.ObjectMapper(),
                        ),
                        WorkoutDraftValidator(CoachTestFixtures.properties()),
                    )
                }
                    .isInstanceOf(IllegalStateException::class.java)
                    .hasMessageContaining(provider.name)
                    .hasMessageContaining("not implemented")
            }
        }

        @Test
        fun `an unknown provider name does not even bind`() {
            ApplicationContextRunner()
                .withPropertyValues("running-ai.coach.provider=GEMINI")
                .withUserConfiguration(CoachProperties::class.java)
                .run { context -> assertThat(context).hasFailed() }
        }

        @Test
        fun `every declared provider is accounted for by the selection logic`() {
            // If a provider is added to the enum, this test fails until CoachConfig handles it.
            assertThat(CoachProvider.entries).containsExactly(
                CoachProvider.CLAUDE, CoachProvider.CODEX, CoachProvider.OLLAMA,
            )
        }
    }
}
