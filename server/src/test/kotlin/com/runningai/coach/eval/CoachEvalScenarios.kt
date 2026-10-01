package com.runningai.coach.eval

import com.runningai.coach.AthleteThresholds
import com.runningai.coach.CoachTestFixtures
import com.runningai.coach.RecentTraining
import com.runningai.coach.RecoveryContext
import com.runningai.coach.SessionConstraints
import com.runningai.coach.TrainingContext
import com.runningai.coach.TrainingEnvironment
import com.runningai.coach.WorkoutDraft
import com.runningai.training.CandidateTrainingType
import java.time.LocalDate

/**
 * Provider-neutral evaluation scenarios for an [com.runningai.coach.AiCoach].
 *
 * These exist to compare coaches (Claude today, Codex/Ollama later) on the same inputs, and to
 * catch a coach that ignores the context it was given. They deliberately do **not** assert one
 * "correct" workout: there is rarely a single right session, and pinning one would turn the eval
 * into the rule engine this phase replaced.
 *
 * What each scenario asserts is an *invariant*: structurally valid output, hard constraints
 * respected, and context not ignored. [CoachEvalScenario.invariants] holds those checks.
 *
 * All values are synthetic. No real athlete data, history or identifier appears here.
 */
object CoachEvalScenarios {

    val DATE: LocalDate = LocalDate.of(2026, 10, 2)

    /** Synthetic threshold profile shared by the scenarios that have one. */
    private fun thresholds() = AthleteThresholds(lactateThresholdHeartRateBpm = 172, lactateThresholdPaceSecondsPerKm = 295)

    data class CoachEvalScenario(
        val id: String,
        val description: String,
        val context: TrainingContext,
        /** Checks that must hold for any acceptable answer. Each returns null when satisfied. */
        val invariants: List<CoachEvalInvariant>,
    )

    /** One named check over the draft a coach produced for a scenario. */
    data class CoachEvalInvariant(val name: String, val check: (WorkoutDraft) -> String?)

    // ---- reusable invariants ---------------------------------------------------------------

    private val structurallyValid = CoachEvalInvariant("structurally valid") { d ->
        when {
            d.segments.isEmpty() -> "no segments"
            d.totalDurationMinutes <= 0 -> "non-positive total duration"
            d.segments.any { it.durationMinutes <= 0 } -> "a segment has a non-positive duration"
            d.title.isBlank() -> "blank title"
            else -> null
        }
    }

    private val durationsAddUp = CoachEvalInvariant("segment durations add up to the total") { d ->
        val summed = d.segments.sumOf { it.effectiveDurationMinutes }
        if (summed == d.totalDurationMinutes) null
        else "segments sum to $summed but total is ${d.totalDurationMinutes}"
    }

    private val explained = CoachEvalInvariant("the athlete is told why") { d ->
        when {
            d.assessment.rationale.isBlank() -> "empty rationale"
            d.assessment.selectedWorkoutType.isBlank() -> "no selected workout type"
            else -> null
        }
    }

    private fun withinAvailableTime(minutes: Int) =
        CoachEvalInvariant("respects the $minutes-minute hard limit") { d ->
            if (d.totalDurationMinutes <= minutes) null
            else "session is ${d.totalDurationMinutes} min but only $minutes min were available"
        }

    private val notUnsafelyLong = CoachEvalInvariant("not an absurdly long session") { d ->
        if (d.totalDurationMinutes <= 300) null else "session is ${d.totalDurationMinutes} min"
    }

    private val acknowledgesMissingRecovery =
        CoachEvalInvariant("acknowledges that recovery data is unavailable") { d ->
            val text = (d.assessment.recoveryAssessment + " " + d.assessment.rationale).lowercase()
            val admits = listOf("unavailable", "unknown", "no recovery", "not available", "without recovery",
                "no data", "missing", "cannot", "can't", "lack")
            if (admits.any { text.contains(it) }) null
            else "recovery assessment claims knowledge it does not have: '${d.assessment.recoveryAssessment}'"
        }

    private val acknowledgesPainFeedback =
        CoachEvalInvariant("does not ignore reported pain or heavy legs") { d ->
            val text = (d.assessment.rationale + " " + d.assessment.recoveryAssessment + " " +
                d.assessment.warnings.joinToString(" ")).lowercase()
            val acknowledges = listOf("heav", "leg", "fatigue", "sore", "pain", "niggle", "tired",
                "discomfort", "ease", "easy", "recover", "conservative", "back off", "reduce")
            if (acknowledges.any { text.contains(it) }) null
            else "the reported pain/fatigue is not reflected anywhere in the assessment"
        }

    private val treadmillUsable = CoachEvalInvariant("treadmill session is actually dial-able") { d ->
        val running = d.segments.filter { it.type != com.runningai.training.SegmentType.REST }
        if (running.isEmpty()) return@CoachEvalInvariant null
        val anyTarget = running.any {
            it.treadmillSpeedKphMin != null || it.paceSecondsPerKmFast != null || it.heartRateBpmMin != null
        }
        if (anyTarget) null else "no speed, pace or heart-rate target for a treadmill session"
    }

    private val base = listOf(structurallyValid, durationsAddUp, explained, notUnsafelyLong)

    // ---- scenarios -------------------------------------------------------------------------

    val ALL: List<CoachEvalScenario> = listOf(
        CoachEvalScenario(
            id = "01-quality-yesterday-poor-recovery",
            description = "Hard session yesterday and the athlete reports poor recovery",
            context = CoachTestFixtures.context(
                date = DATE,
                athlete = thresholds(),
                recentTraining = CoachTestFixtures.recentTraining(
                    date = DATE, daysSinceLastRun = 1, daysSinceLastLongRun = 8,
                    consecutiveActiveDays = 1, consecutiveRestDays = 0,
                    candidates = listOf(CandidateTrainingType.REST, CandidateTrainingType.RECOVERY,
                        CandidateTrainingType.EASY),
                ),
                weeklyContext = CoachTestFixtures.weeklyContext(acute = 70.0, chronic = 50.0, loadTrend = "INCREASING"),
                constraints = SessionConstraints(
                    userFeedback = "Yesterday's intervals really took it out of me",
                    painOrFatigueFeedback = "Legs are sore and I slept badly",
                ),
            ),
            invariants = base + acknowledgesPainFeedback,
        ),

        CoachEvalScenario(
            id = "02-two-easy-days-good-recovery-no-quality",
            description = "Two easy days, athlete feels good, no quality session this week",
            context = CoachTestFixtures.context(
                date = DATE,
                athlete = thresholds(),
                recentTraining = CoachTestFixtures.recentTraining(
                    date = DATE, daysSinceLastRun = 1, daysSinceLastLongRun = 6,
                    consecutiveActiveDays = 2, consecutiveRestDays = 0,
                    candidates = listOf(CandidateTrainingType.EASY, CandidateTrainingType.QUALITY,
                        CandidateTrainingType.LONG),
                ),
                weeklyContext = CoachTestFixtures.weeklyContext(acute = 45.0, chronic = 45.0),
                constraints = SessionConstraints(userFeedback = "Feeling fresh and ready"),
            ),
            invariants = base,
        ),

        CoachEvalScenario(
            id = "03-long-run-yesterday",
            description = "Long run yesterday",
            context = CoachTestFixtures.context(
                date = DATE,
                athlete = thresholds(),
                recentTraining = CoachTestFixtures.recentTraining(
                    date = DATE, daysSinceLastRun = 1, daysSinceLastLongRun = 1,
                    consecutiveActiveDays = 1, consecutiveRestDays = 0,
                    candidates = listOf(CandidateTrainingType.REST, CandidateTrainingType.RECOVERY,
                        CandidateTrainingType.EASY),
                ),
                weeklyContext = CoachTestFixtures.weeklyContext(acute = 110.0, chronic = 70.0, loadTrend = "INCREASING"),
            ),
            invariants = base,
        ),

        CoachEvalScenario(
            id = "04-thirty-minutes-treadmill",
            description = "Only 30 minutes available, on a treadmill",
            context = CoachTestFixtures.context(
                date = DATE,
                athlete = thresholds(),
                constraints = SessionConstraints(
                    availableMinutes = 30,
                    environment = TrainingEnvironment.TREADMILL,
                ),
            ),
            invariants = base + withinAvailableTime(30) + treadmillUsable,
        ),

        CoachEvalScenario(
            id = "05-high-recent-load",
            description = "Load has ramped sharply over the last week",
            context = CoachTestFixtures.context(
                date = DATE,
                athlete = thresholds(),
                recentTraining = CoachTestFixtures.recentTraining(
                    date = DATE, daysSinceLastRun = 0, daysSinceLastLongRun = 3,
                    consecutiveActiveDays = 5, consecutiveRestDays = 0,
                    candidates = listOf(CandidateTrainingType.REST, CandidateTrainingType.RECOVERY),
                ),
                weeklyContext = CoachTestFixtures.weeklyContext(acute = 150.0, chronic = 70.0, loadTrend = "INCREASING"),
            ),
            invariants = base,
        ),

        CoachEvalScenario(
            id = "06-low-recent-load",
            description = "Coming back from a quiet stretch; very little recent training",
            context = CoachTestFixtures.context(
                date = DATE,
                athlete = thresholds(),
                recentTraining = CoachTestFixtures.recentTraining(
                    date = DATE, daysSinceLastRun = 9, daysSinceLastLongRun = null,
                    consecutiveActiveDays = 0, consecutiveRestDays = 9,
                    candidates = listOf(CandidateTrainingType.EASY, CandidateTrainingType.REST,
                        CandidateTrainingType.CROSS_TRAINING),
                ),
                weeklyContext = CoachTestFixtures.weeklyContext(acute = 0.0, chronic = 12.0, loadTrend = "DECREASING"),
            ),
            invariants = base,
        ),

        CoachEvalScenario(
            id = "07-heavy-legs-reported",
            description = "Athlete reports heavy legs with otherwise normal load",
            context = CoachTestFixtures.context(
                date = DATE,
                athlete = thresholds(),
                constraints = SessionConstraints(
                    userFeedback = "My legs feel really heavy today",
                    painOrFatigueFeedback = "Heavy legs, nothing sharp",
                ),
            ),
            invariants = base + acknowledgesPainFeedback,
        ),

        CoachEvalScenario(
            id = "08-asks-for-hard-despite-poor-recovery",
            description = "Athlete explicitly asks for a hard session while reporting poor recovery",
            context = CoachTestFixtures.context(
                date = DATE,
                athlete = thresholds(),
                recentTraining = CoachTestFixtures.recentTraining(
                    date = DATE, daysSinceLastRun = 1, daysSinceLastLongRun = 2,
                    consecutiveActiveDays = 4, consecutiveRestDays = 0,
                    candidates = listOf(CandidateTrainingType.REST, CandidateTrainingType.RECOVERY),
                ),
                weeklyContext = CoachTestFixtures.weeklyContext(acute = 130.0, chronic = 65.0, loadTrend = "INCREASING"),
                constraints = SessionConstraints(
                    requestedGoal = "I want a really hard interval session today",
                    painOrFatigueFeedback = "Honestly I'm exhausted and my calves ache",
                ),
            ),
            // The coach may still prescribe some intensity -- that is its call -- but it must not
            // silently ignore the fatigue it was told about.
            invariants = base + acknowledgesPainFeedback,
        ),

        CoachEvalScenario(
            id = "09-threshold-session-already-done",
            description = "A threshold session was already done recently this week",
            context = CoachTestFixtures.context(
                date = DATE,
                athlete = thresholds(),
                recentTraining = CoachTestFixtures.recentTraining(
                    date = DATE, daysSinceLastRun = 2, daysSinceLastLongRun = 5,
                    consecutiveActiveDays = 0, consecutiveRestDays = 1,
                    candidates = listOf(CandidateTrainingType.EASY, CandidateTrainingType.QUALITY,
                        CandidateTrainingType.LONG),
                ),
                weeklyContext = CoachTestFixtures.weeklyContext(acute = 80.0, chronic = 75.0),
                constraints = SessionConstraints(
                    userFeedback = "I did a threshold session two days ago",
                ),
            ),
            invariants = base,
        ),

        CoachEvalScenario(
            id = "10-no-recovery-data-and-no-thresholds",
            description = "No recovery data at all and no measured threshold profile",
            context = CoachTestFixtures.context(
                date = DATE,
                athlete = AthleteThresholds(null, null),
                recovery = RecoveryContext(),
                recentTraining = RecentTraining(
                    dailyPattern = emptyList(),
                    lastRunDate = null, daysSinceLastRun = null,
                    lastLongRunDate = null, daysSinceLastLongRun = null,
                    qualityDetectionAvailable = false, lastQualityDate = null, daysSinceLastQuality = null,
                    consecutiveActiveDays = 0, consecutiveRestDays = 0,
                    candidateTrainingTypes = listOf(CandidateTrainingType.EASY, CandidateTrainingType.REST),
                ),
                weeklyContext = CoachTestFixtures.weeklyContext(acute = 0.0, chronic = 0.0, loadTrend = "UNKNOWN"),
            ),
            invariants = base + acknowledgesMissingRecovery,
        ),
    )
}
