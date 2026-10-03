package com.runningai.coach

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import java.time.Duration

/**
 * AI coach configuration. Holds no credential of any kind: the Claude provider authenticates
 * through the Claude Code CLI's own login session on the host, and no API key is read, stored or
 * forwarded anywhere in this package.
 */
@ConfigurationProperties(prefix = "running-ai.coach")
data class CoachProperties(
    /** Which backend serves [AiCoach]. Only CLAUDE is implemented in this build. */
    @param:DefaultValue("CLAUDE") val provider: CoachProvider,
    @param:DefaultValue val claude: Claude,
    @param:DefaultValue val validation: Validation,
    /**
     * Which [CoachTrainingContext] [CoachTrainingContextBuilder] builds (Phase 6H-7). Defaults to V1
     * for backward compatibility: V2 is activated deliberately, on this Main PC only after its live
     * validation passes (`RUNNING_AI_TRAINING_CONTEXT_VERSION=V2`), never automatically.
     */
    @param:DefaultValue("V1") val contextVersion: ContextVersion = ContextVersion.V1,
) {

    data class Claude(
        /** Executable name or absolute path; resolved on PATH when not absolute. */
        @param:DefaultValue("claude") val command: String,
        /** Model alias or full id passed to `--model`. */
        @param:DefaultValue("sonnet") val model: String,
        /** Hard wall-clock limit for one coach call; the process is destroyed on expiry. */
        @param:DefaultValue("120s") val timeout: Duration,
        /** Cap on captured stdout, so a runaway response cannot exhaust memory. */
        @param:DefaultValue("1048576") val maxOutputBytes: Int,
        /** Cap on captured stderr (diagnostics only, never echoed to an API response). */
        @param:DefaultValue("65536") val maxErrorBytes: Int,
    )

    data class Validation(
        /**
         * Absurd-duration ceiling. A hard safety stop against a malformed or runaway response, not
         * a coaching policy: it is far above any session this system would legitimately design, so
         * it never second-guesses the coach's judgement about a long run.
         */
        @param:DefaultValue("300") val maxTotalDurationMinutes: Int,
        /** Smallest total duration that still counts as a workout. */
        @param:DefaultValue("5") val minTotalDurationMinutes: Int,
        /**
         * How far a segment's declared pace target may deviate from the athlete's threshold pace
         * before it is rejected as physically implausible rather than merely aggressive, as a
         * fraction of threshold pace. 0.5 = between half and double the threshold pace. Wide on
         * purpose: it exists to catch unit errors and nonsense, not to narrow coaching choices.
         */
        @param:DefaultValue("0.5") val pacePlausibilityTolerance: Double,
        /**
         * Same idea for heart rate, as a fraction of LTHR: a target outside this band around LTHR
         * is a data error, not a training decision.
         */
        @param:DefaultValue("0.5") val heartRatePlausibilityTolerance: Double,
    )
}
