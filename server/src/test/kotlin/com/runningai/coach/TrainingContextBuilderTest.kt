package com.runningai.coach

import com.runningai.activity.Activity
import com.runningai.activity.ActivityRepository
import com.runningai.activity.ActivityType
import com.runningai.activity.ExternalSource
import com.runningai.athlete.AthleteIntensityProfileRequest
import com.runningai.athlete.AthleteIntensityProfileService
import com.runningai.athlete.AthleteService
import com.runningai.recovery.RecoveryDailyValues
import com.runningai.recovery.RecoverySnapshotService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The context handed to the AI is built from the REAL repositories and services, not from a stub:
 * these tests insert actual activities and an actual intensity profile and assert that the numbers
 * the coach sees are the ones RunningAI computed.
 *
 * All athlete values here are synthetic.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TrainingContextBuilderTest {

    @Autowired private lateinit var builder: TrainingContextBuilder
    @Autowired private lateinit var activityRepository: ActivityRepository
    @Autowired private lateinit var profileService: AthleteIntensityProfileService
    @Autowired private lateinit var athleteService: AthleteService
    @Autowired private lateinit var recoverySnapshots: RecoverySnapshotService

    private val asOf: LocalDate = LocalDate.of(2026, 10, 2)
    private val zone = ZoneId.of("Asia/Seoul")
    private var athleteId: Long = 0

    @BeforeEach
    fun setUp() {
        athleteId = athleteService.getDefaultAthlete().id
    }

    private fun run(daysAgo: Long, minutes: Long, meters: Double, type: ActivityType = ActivityType.RUN) {
        val startedAt: Instant = asOf.minusDays(daysAgo).atTime(7, 0).atZone(zone).toInstant()
        activityRepository.save(
            Activity(
                athleteId, ExternalSource.GARMIN, "synthetic-$daysAgo-$type", type, startedAt,
                Duration.ofMinutes(minutes).seconds.toInt(), meters, null, null,
            ),
        )
    }

    @Test
    fun `the athlete thresholds come from the stored intensity profile`() {
        profileService.replaceDefaultProfile(AthleteIntensityProfileRequest(175, 295))

        val context = builder.build(asOf)

        assertThat(context.athlete.lactateThresholdHeartRateBpm).isEqualTo(175)
        assertThat(context.athlete.lactateThresholdPaceSecondsPerKm).isEqualTo(295)
    }

    @Test
    fun `a missing intensity profile yields nulls, not invented thresholds`() {
        val context = builder.build(asOf)

        assertThat(context.athlete.lactateThresholdHeartRateBpm).isNull()
        assertThat(context.athlete.lactateThresholdPaceSecondsPerKm).isNull()
    }

    @Test
    fun `real activities drive the weekly load and the daily pattern`() {
        run(daysAgo = 1, minutes = 45, meters = 9_000.0)
        run(daysAgo = 3, minutes = 30, meters = 6_000.0)

        val context = builder.build(asOf)

        assertThat(context.weeklyContext.current7DayLoadMinutes).isEqualTo(75.0)
        assertThat(context.weeklyContext.activeDays7Days).isEqualTo(2)
        assertThat(context.weeklyContext.restDays7Days).isEqualTo(5)
        assertThat(context.weeklyContext.running7DayDurationSeconds).isEqualTo(75 * 60L)
        assertThat(context.weeklyContext.running7DayDistanceMeters).isEqualTo(15_000.0)
        assertThat(context.recentTraining.lastRunDate).isEqualTo(asOf.minusDays(1))
        assertThat(context.recentTraining.daysSinceLastRun).isEqualTo(1)
        assertThat(context.recentTraining.dailyPattern).isNotEmpty
        assertThat(context.recentTraining.dailyPattern.last().date).isEqualTo(asOf)
    }

    @Test
    fun `an empty history produces zeros and nulls rather than fabricated history`() {
        val context = builder.build(asOf)

        assertThat(context.weeklyContext.current7DayLoadMinutes).isZero()
        assertThat(context.weeklyContext.acuteChronicRatio).isNull()
        assertThat(context.weeklyContext.weeklyLoadChangePercent).isNull()
        assertThat(context.recentTraining.lastRunDate).isNull()
        assertThat(context.recentTraining.daysSinceLastRun).isNull()
        assertThat(context.recentTraining.lastLongRunDate).isNull()
    }

    @Test
    fun `a long run is reflected in the last long run date`() {
        run(daysAgo = 2, minutes = 95, meters = 18_000.0)

        val context = builder.build(asOf)

        assertThat(context.recentTraining.lastLongRunDate).isEqualTo(asOf.minusDays(2))
        assertThat(context.recentTraining.daysSinceLastLongRun).isEqualTo(2)
    }

    @Test
    fun `quality detection is reported as unavailable rather than as no quality done`() {
        val context = builder.build(asOf)

        assertThat(context.recentTraining.qualityDetectionAvailable).isFalse()
        assertThat(context.recentTraining.lastQualityDate).isNull()
        assertThat(context.recentTraining.daysSinceLastQuality).isNull()
    }

    @Test
    fun `with no stored recovery snapshot every recovery metric is null`() {
        val context = builder.build(asOf)

        assertThat(context.recovery.hrv).isNull()
        assertThat(context.recovery.restingHeartRate).isNull()
        assertThat(context.recovery.sleep).isNull()
        assertThat(context.recovery.bodyBattery).isNull()
        assertThat(context.recovery.stress).isNull()
        assertThat(context.recovery.anyAvailable).isFalse()
    }

    @Test
    fun `stored Garmin recovery snapshots reach the coach with their personal baseline`() {
        // 10 synthetic baseline days, then today's reading
        (1L..10L).forEach { recoverySnapshots.upsert(asOf.minusDays(it), recovery(hrv = 50.0, rhr = 50, sleepSeconds = 27_000)) }
        recoverySnapshots.upsert(asOf, recovery(hrv = 40.0, rhr = 55, sleepSeconds = 18_000))

        val recovery = builder.build(asOf).recovery

        assertThat(recovery.hrv!!.lastNightAvgMs.current).isEqualTo(40.0)
        assertThat(recovery.hrv!!.lastNightAvgMs.baseline).isEqualTo(50.0)
        assertThat(recovery.hrv!!.lastNightAvgMs.differencePercent).isEqualTo(-20.0)
        assertThat(recovery.hrv!!.lastNightAvgMs.sampleCount).isEqualTo(10)
        assertThat(recovery.hrv!!.garminHrvStatus).isEqualTo("BALANCED")
        assertThat(recovery.restingHeartRate!!.bpm.difference).isEqualTo(5.0)
        assertThat(recovery.sleep!!.durationHours!!.current).isEqualTo(5.0)
        assertThat(recovery.sleep!!.durationHours!!.baseline).isEqualTo(7.5)
        // never ingested in this fixture, so never invented
        assertThat(recovery.bodyBattery).isNull()
        assertThat(recovery.stress).isNull()
        assertThat(recovery.sleep!!.sleepScore).isNull()
    }

    @Test
    fun `a recovery snapshot after the as-of date never leaks into the context`() {
        recoverySnapshots.upsert(asOf.plusDays(1), recovery(hrv = 60.0, rhr = 48, sleepSeconds = 30_000))

        assertThat(builder.build(asOf).recovery.anyAvailable).isFalse()
    }

    private fun recovery(hrv: Double, rhr: Int, sleepSeconds: Int) = RecoveryDailyValues(
        hrv, null, "BALANCED", sleepSeconds, null, rhr, null, null, null, null, null, null,
    )

    @Test
    fun `constraints are carried through unchanged`() {
        val constraints = SessionConstraints(
            availableMinutes = 30,
            environment = TrainingEnvironment.TREADMILL,
            userFeedback = "legs feel heavy",
            requestedGoal = "half marathon in spring",
            painOrFatigueFeedback = "slight achilles niggle",
        )

        val context = builder.build(asOf, constraints)

        assertThat(context.constraints).isEqualTo(constraints)
        assertThat(context.date).isEqualTo(asOf)
    }

    @Test
    fun `no constraints yields an all-null constraint block, not defaults`() {
        val context = builder.build(asOf)

        assertThat(context.constraints.availableMinutes).isNull()
        assertThat(context.constraints.environment).isNull()
        assertThat(context.constraints.userFeedback).isNull()
        assertThat(context.constraints.painOrFatigueFeedback).isNull()
    }

    @Test
    fun `an activity after the as-of date never leaks into the context`() {
        run(daysAgo = -1, minutes = 60, meters = 12_000.0)   // tomorrow

        val context = builder.build(asOf)

        assertThat(context.weeklyContext.current7DayLoadMinutes).isZero()
        assertThat(context.recentTraining.lastRunDate).isNull()
    }

    @Test
    fun `candidate training types come from the existing decision pipeline`() {
        val context = builder.build(asOf)

        // The coach is given candidates as context; it is not required to pick one of them.
        assertThat(context.recentTraining.candidateTrainingTypes).isNotEmpty
    }
}
