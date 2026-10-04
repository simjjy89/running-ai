package com.runningai.coach.claude

import com.runningai.coach.CoachTestFixtures
import com.runningai.coach.SessionConstraints
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Phase 6H-7.2: a Korean [SessionConstraints] string (or a revision's free-text request) must
 * survive into the generated prompt as the same Unicode string - escaped JSON is fine, but it
 * must decode back to the original text. No process, no network; the fixture text is synthetic.
 */
class ClaudeCoachPromptBuilderUtf8Test {

    private val builder = ClaudeCoachPromptBuilder()

    @Test
    fun `createPrompt carries a Korean requestedGoal unmangled, even if JSON-escaped`() {
        val goal = "하프마라톤 일주일 전 가볍게 훈련하고 싶어요"
        val context = CoachTestFixtures.context(
            constraints = SessionConstraints(requestedGoal = goal),
        )

        val prompt = builder.createPrompt(context)

        assertThat(containsUnicodeTextOrItsJsonEscape(prompt, goal)).isTrue()
    }

    @Test
    fun `revisePrompt carries the athlete's Korean request verbatim`() {
        val request = "오늘 다리가 무거워. 인터벌 대신 지속주 형태로 바꿔줘."
        val context = CoachTestFixtures.context()
        val previousDraft = CoachTestFixtures.draft(date = context.date)

        val prompt = builder.revisePrompt(context, previousDraft, request)

        assertThat(prompt).contains(request)
    }

    /** The snapshot JSON may escape non-ASCII as \\uXXXX; either the raw text or that escape counts. */
    private fun containsUnicodeTextOrItsJsonEscape(prompt: String, text: String): Boolean {
        if (prompt.contains(text)) return true
        val escaped = text.map { c -> if (c.code > 127) "\\u%04x".format(c.code) else c.toString() }.joinToString("")
        return prompt.contains(escaped)
    }
}
