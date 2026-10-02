package com.runningai.coach

import com.runningai.recovery.BaselineStatus
import com.runningai.training.CandidateTrainingType
import com.runningai.training.IntensityClass
import com.runningai.training.SegmentType
import java.time.LocalDate

/**
 * Synthetic coach-domain fixtures. Every number here is invented for testing: no real athlete
 * value, no real training history and no personal identifier appears in this file.
 */
object CoachTestFixtures {

    val DATE: LocalDate = LocalDate.of(2026, 10, 2)

    /** Synthetic threshold profile (NOT any real athlete's values). */
    fun thresholds(bpm: Int? = 170, paceSecPerKm: Int? = 300) = AthleteThresholds(bpm, paceSecPerKm)

    fun trainingDay(
        date: LocalDate,
        classification: String = "REST",
        loadMinutes: Double = 0.0,
        runningSeconds: Long = 0,
        runningMeters: Double = 0.0,
    ) = TrainingDay(date, classification, if (loadMinutes > 0) 1 else 0, loadMinutes, runningSeconds, runningMeters, 0)

    fun context(
        date: LocalDate = DATE,
        athlete: AthleteThresholds = thresholds(),
        recentTraining: RecentTraining = recentTraining(date),
        recovery: RecoveryContext = RecoveryContext(),
        weeklyContext: WeeklyContext = weeklyContext(),
        constraints: SessionConstraints = SessionConstraints(),
    ) = TrainingContext(date, athlete, recentTraining, recovery, weeklyContext, constraints)

    fun recentTraining(
        date: LocalDate = DATE,
        daysSinceLastRun: Int? = 2,
        daysSinceLastLongRun: Int? = 9,
        consecutiveActiveDays: Int = 0,
        consecutiveRestDays: Int = 2,
        candidates: List<CandidateTrainingType> = listOf(
            CandidateTrainingType.EASY, CandidateTrainingType.QUALITY, CandidateTrainingType.LONG,
        ),
        dailyPattern: List<TrainingDay> = (6 downTo 0).map { trainingDay(date.minusDays(it.toLong())) },
    ) = RecentTraining(
        dailyPattern = dailyPattern,
        lastRunDate = daysSinceLastRun?.let { date.minusDays(it.toLong()) },
        daysSinceLastRun = daysSinceLastRun,
        lastLongRunDate = daysSinceLastLongRun?.let { date.minusDays(it.toLong()) },
        daysSinceLastLongRun = daysSinceLastLongRun,
        qualityDetectionAvailable = false,
        lastQualityDate = null,
        daysSinceLastQuality = null,
        consecutiveActiveDays = consecutiveActiveDays,
        consecutiveRestDays = consecutiveRestDays,
        candidateTrainingTypes = candidates,
    )

    /**
     * A synthetic recovery measurement, built the way [RecoveryContextBuilder] reports one:
     * difference = current - baseline, percentage relative to baseline. `baseline = null` means
     * INSUFFICIENT_DATA.
     */
    fun measurement(
        current: Double,
        baseline: Double?,
        ageDays: Int = 0,
        date: LocalDate = DATE,
        sampleCount: Int = if (baseline == null) 3 else 21,
    ): RecoveryMeasurement {
        val difference = baseline?.let { Math.round((current - it) * 10) / 10.0 }
        val percent = baseline?.takeIf { it != 0.0 }?.let { Math.round((current - it) / it * 1000) / 10.0 }
        return RecoveryMeasurement(
            date = date.minusDays(ageDays.toLong()),
            ageDays = ageDays,
            current = current,
            baseline = baseline,
            difference = difference,
            differencePercent = percent,
            sampleCount = sampleCount,
            baselineWindowDays = 28,
            minimumSamples = 7,
            baselineStatus = if (baseline == null) BaselineStatus.INSUFFICIENT_DATA else BaselineStatus.AVAILABLE,
        )
    }

    /** A complete synthetic recovery context where every metric sits exactly on its baseline. */
    fun normalRecovery(ageDays: Int = 0, date: LocalDate = DATE) = RecoveryContext(
        hrv = HrvRecovery(measurement(52.0, 52.0, ageDays, date), 51.0, "BALANCED"),
        sleep = SleepRecovery(measurement(7.4, 7.4, ageDays, date), measurement(80.0, 80.0, ageDays, date)),
        restingHeartRate = RestingHeartRateRecovery(measurement(50.0, 50.0, ageDays, date)),
        bodyBattery = BodyBatteryRecovery(measurement(78.0, 78.0, ageDays, date), 25, 55, 50),
        stress = StressRecovery(measurement(26.0, 26.0, ageDays, date), 80),
    )

    fun weeklyContext(
        acute: Double = 40.0,
        chronic: Double = 35.0,
        loadTrend: String = "STABLE",
    ) = WeeklyContext(
        acuteLoadMinutes = acute,
        chronicLoadMinutes = chronic,
        acuteChronicRatio = if (chronic == 0.0) null else acute / chronic,
        current7DayLoadMinutes = acute,
        previous7DayLoadMinutes = chronic,
        weeklyLoadChangePercent = if (chronic == 0.0) null else (acute - chronic) / chronic * 100,
        running7DayDistanceMeters = 25_000.0,
        running7DayDurationSeconds = 9_000,
        rampLoadMinutes = acute - chronic,
        monotony = 0.8,
        strain = acute * 0.8,
        activeDays7Days = 3,
        restDays7Days = 4,
        loadTrend = loadTrend,
    )

    fun assessment(
        recovery: String = "Recovery data unavailable; treating readiness as unknown.",
        load: String = "Load has been steady over the last two weeks.",
        type: String = "EASY",
        rationale: String = "A steady easy run keeps the week consistent without adding fatigue.",
        warnings: List<String> = emptyList(),
    ) = CoachAssessment(recovery, load, type, rationale, warnings)

    fun segment(
        type: SegmentType = SegmentType.MAIN,
        durationMinutes: Int = 30,
        intensity: IntensityClass = IntensityClass.EASY,
        paceFast: Int? = null,
        paceSlow: Int? = null,
        hrMin: Int? = null,
        hrMax: Int? = null,
        repetitions: Int? = null,
        recoveryMinutes: Int? = null,
        speedMin: Double? = null,
        speedMax: Double? = null,
        inclineMin: Double? = null,
        inclineMax: Double? = null,
    ) = WorkoutDraftSegment(
        type = type,
        durationMinutes = durationMinutes,
        intensity = intensity,
        description = null,
        paceSecondsPerKmFast = paceFast,
        paceSecondsPerKmSlow = paceSlow,
        heartRateBpmMin = hrMin,
        heartRateBpmMax = hrMax,
        treadmillSpeedKphMin = speedMin,
        treadmillSpeedKphMax = speedMax,
        inclinePercentMin = inclineMin,
        inclinePercentMax = inclineMax,
        repetitions = repetitions,
        recoveryDurationMinutes = recoveryMinutes,
    )

    /** A valid draft: segments sum exactly to the declared total. */
    fun draft(
        date: LocalDate = DATE,
        version: Int = 1,
        title: String = "Steady Easy Run",
        workoutType: String = "EASY",
        segments: List<WorkoutDraftSegment> = listOf(
            segment(SegmentType.WARM_UP, 10, IntensityClass.VERY_EASY),
            segment(SegmentType.MAIN, 25, IntensityClass.EASY),
            segment(SegmentType.COOL_DOWN, 5, IntensityClass.VERY_EASY),
        ),
        totalDurationMinutes: Int = segments.sumOf { it.effectiveDurationMinutes },
        assessment: CoachAssessment = assessment(type = workoutType),
        status: WorkoutDraftStatus = WorkoutDraftStatus.DRAFT,
    ) = WorkoutDraft(
        version = version,
        date = date,
        title = title,
        workoutType = workoutType,
        totalDurationMinutes = totalDurationMinutes,
        assessment = assessment,
        segments = segments,
        provider = CoachProvider.CLAUDE,
        model = "test-model",
        status = status,
    )

    fun properties(
        provider: CoachProvider = CoachProvider.CLAUDE,
        command: String = "claude",
        maxTotalDurationMinutes: Int = 300,
        minTotalDurationMinutes: Int = 5,
        timeoutSeconds: Long = 120,
    ) = CoachProperties(
        provider = provider,
        claude = CoachProperties.Claude(
            command = command,
            model = "test-model",
            timeout = java.time.Duration.ofSeconds(timeoutSeconds),
            maxOutputBytes = 1_048_576,
            maxErrorBytes = 65_536,
        ),
        validation = CoachProperties.Validation(
            maxTotalDurationMinutes = maxTotalDurationMinutes,
            minTotalDurationMinutes = minTotalDurationMinutes,
            pacePlausibilityTolerance = 0.5,
            heartRatePlausibilityTolerance = 0.5,
        ),
    )
}

/**
 * Deterministic [AiCoach] for tests and for the CI run of the evaluation scenarios: it returns a
 * pre-set draft (or throws a pre-set failure) and records what it was asked, so a test can assert
 * on the context the coach actually received. It never touches a network or a process, which is
 * what keeps `gradlew test` free of live Claude calls.
 */
class FakeAiCoach(
    private var nextDraft: (TrainingContext) -> WorkoutDraft = { CoachTestFixtures.draft(date = it.date) },
) : AiCoach {

    var failWith: RuntimeException? = null
    val createdContexts = mutableListOf<TrainingContext>()
    val revisedContexts = mutableListOf<TrainingContext>()
    val revisionRequests = mutableListOf<String>()
    val revisedDrafts = mutableListOf<WorkoutDraft>()

    fun respondWith(block: (TrainingContext) -> WorkoutDraft) {
        nextDraft = block
    }

    /**
     * Clears the scripted behaviour and every recording. Required between tests that share one
     * Spring context, where this is a singleton bean and recordings would otherwise accumulate.
     */
    fun reset() {
        failWith = null
        nextDraft = { CoachTestFixtures.draft(date = it.date) }
        createdContexts.clear()
        revisedContexts.clear()
        revisionRequests.clear()
        revisedDrafts.clear()
    }

    override fun createWorkout(context: TrainingContext): WorkoutDraft {
        createdContexts += context
        failWith?.let { throw it }
        return nextDraft(context)
    }

    override fun reviseWorkout(
        context: TrainingContext,
        currentDraft: WorkoutDraft,
        userRequest: String,
    ): WorkoutDraft {
        revisedContexts += context
        revisedDrafts += currentDraft
        revisionRequests += userRequest
        failWith?.let { throw it }
        return nextDraft(context).copy(
            version = currentDraft.version + 1,
            draftGroupId = currentDraft.draftGroupId,
        )
    }
}
