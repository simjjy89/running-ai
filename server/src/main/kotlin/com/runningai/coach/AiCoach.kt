package com.runningai.coach

/**
 * The thing that actually analyses the training and designs the workout.
 *
 * Deliberately vendor-neutral: no implementation type, transport, prompt, model id or provider SDK
 * appears in this contract, so swapping Claude for another backend is a bean change, not a caller
 * change. Implementations must not decide anything *outside* workout design — in particular they
 * never publish, never write to Intervals or Garmin, and never persist.
 */
interface AiCoach {

    /** Designs today's workout from [context] alone. */
    fun createWorkout(context: CoachTrainingContext): WorkoutDraft

    /**
     * Redesigns the workout after the athlete asked for a change in their own words.
     *
     * The coach gets the original [context], the [currentDraft] it previously produced and the
     * natural-language [userRequest], and designs the session again. Spring must not mechanically
     * patch a duration or an intensity instead of calling this.
     */
    fun reviseWorkout(
        context: CoachTrainingContext,
        currentDraft: WorkoutDraft,
        userRequest: String,
    ): WorkoutDraft
}

/** Why a coach call could not produce a usable draft. Carries no prompt text and no credential. */
class AiCoachException(
    val reason: Reason,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {

    enum class Reason {
        /** The provider CLI/binary is not installed or not on PATH. */
        PROVIDER_UNAVAILABLE,

        /** The provider is installed but has no usable login session. */
        AUTH_REQUIRED,

        /** The call exceeded the configured timeout and was killed. */
        TIMEOUT,

        /** The provider ran but reported a failure. */
        PROVIDER_ERROR,

        /** The provider answered, but not with the agreed JSON contract. */
        INVALID_RESPONSE,

        /** The response parsed but failed hard-safety validation. */
        VALIDATION_FAILED,
    }
}
