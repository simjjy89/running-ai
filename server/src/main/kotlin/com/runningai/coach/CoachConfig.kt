package com.runningai.coach

import com.runningai.coach.claude.ClaudeAiCoach
import com.runningai.coach.claude.ClaudeCliClient
import com.runningai.coach.claude.ClaudeCoachPromptBuilder
import com.runningai.coach.claude.ClaudeCoachResponseParser
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Selects the [AiCoach] implementation from configuration. Callers depend only on the [AiCoach]
 * interface, so adding a provider later is a new branch here plus a new class — never a change to
 * the draft service or the controller.
 *
 * A configured-but-unimplemented provider fails at startup with an explicit message rather than
 * silently falling back to Claude: quietly using a different AI than the operator asked for would
 * be worse than not starting.
 */
@Configuration
class CoachConfig {

    @Bean
    fun aiCoach(
        properties: CoachProperties,
        promptBuilder: ClaudeCoachPromptBuilder,
        cliClient: ClaudeCliClient,
        parser: ClaudeCoachResponseParser,
        validator: WorkoutDraftValidator,
    ): AiCoach = when (properties.provider) {
        CoachProvider.CLAUDE -> ClaudeAiCoach(promptBuilder, cliClient, parser, validator, properties)
        CoachProvider.CODEX, CoachProvider.OLLAMA -> throw IllegalStateException(
            "running-ai.coach.provider=${properties.provider} is not implemented in this build; " +
                "only CLAUDE is available.",
        )
    }
}
