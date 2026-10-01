package com.runningai.coach

/**
 * AI backends an [AiCoach] can be implemented against. Only [CLAUDE] is implemented in Phase 6E;
 * the others exist so the configuration surface and the selection logic are already provider-neutral
 * and a future implementation is a new bean, not a change to callers.
 */
enum class CoachProvider {
    CLAUDE,
    CODEX,
    OLLAMA,
}
