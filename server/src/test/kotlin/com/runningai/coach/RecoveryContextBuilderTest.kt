package com.runningai.coach

import com.runningai.coach.claude.ClaudeCoachPromptBuilder
import com.runningai.recovery.BaselineStatus
import com.runningai.recovery.RecoveryBaselineService
import com.runningai.recovery.RecoveryDailyValues
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Pure mapping of the baseline computation into the coach's [RecoveryContext], and how that context
 * appears in the prompt. All values are synthetic.
 */
class RecoveryContextBuilderTest {

    private val target: LocalDate = LocalDate.of(2026, 10, 2)

    private fun values(
        hrv: Double? = null, hrvWeekly: Double? = null, hrvStatus: String? = null,
        sleepSeconds: Int? = null, sleepScore: Int? = null, rhr: Int? = null,
        bbHigh: Int? = null, bbLow: Int? = null, bbCharged: Int? = null, bbDrained: Int? = null,
        stressAvg: Int? = null, stressMax: Int? = null,
    ) = RecoveryDailyValues(hrv, hrvWeekly, hrvStatus, sleepSeconds, sleepScore, rhr, bbHigh, bbLow, bbCharged,
        bbDrained, stressAvg, stressMax)

    private fun context(days: Map<LocalDate, RecoveryDailyValues>): RecoveryContext =
        RecoveryContextBuilder.from(RecoveryBaselineService.compute(target, days))

    private fun history(count: Int, v: RecoveryDailyValues) = (1..count).associate { target.minusDays(it.toLong()) to v }

    @Test
    fun `every metric group is mapped with its baseline and same-day extras`() {
        val days = history(
            10,
            values(hrv = 50.0, sleepSeconds = 27_000, sleepScore = 80, rhr = 50, bbHigh = 80, stressAvg = 25),
        ) + (target to values(
            hrv = 40.0, hrvWeekly = 47.5, hrvStatus = "UNBALANCED", sleepSeconds = 19_800, sleepScore = 60, rhr = 56,
            bbHigh = 40, bbLow = 15, bbCharged = 20, bbDrained = 45, stressAvg = 40, stressMax = 95,
        ))

        val r = context(days)

        assertThat(r.hrv!!.lastNightAvgMs.current).isEqualTo(40.0)
        assertThat(r.hrv!!.lastNightAvgMs.baseline).isEqualTo(50.0)
        assertThat(r.hrv!!.lastNightAvgMs.differencePercent).isEqualTo(-20.0)
        assertThat(r.hrv!!.garminHrvStatus).isEqualTo("UNBALANCED")
        assertThat(r.hrv!!.garminWeeklyAvgMs).isEqualTo(47.5)
        assertThat(r.sleep!!.durationHours!!.current).isEqualTo(5.5)
        assertThat(r.sleep!!.durationHours!!.baseline).isEqualTo(7.5)
        assertThat(r.sleep!!.durationHours!!.difference).isEqualTo(-2.0)
        assertThat(r.sleep!!.sleepScore!!.current).isEqualTo(60.0)
        assertThat(r.restingHeartRate!!.bpm.difference).isEqualTo(6.0)
        assertThat(r.bodyBattery!!.highest.current).isEqualTo(40.0)
        assertThat(r.bodyBattery!!.lowest).isEqualTo(15)
        assertThat(r.bodyBattery!!.charged).isEqualTo(20)
        assertThat(r.bodyBattery!!.drained).isEqualTo(45)
        assertThat(r.stress!!.average.differencePercent).isEqualTo(60.0)
        assertThat(r.stress!!.max).isEqualTo(95)
        assertThat(r.anyAvailable).isTrue()
    }

    @Test
    fun `no snapshot at all gives an all-null context`() {
        val r = context(emptyMap())

        assertThat(r).isEqualTo(RecoveryContext())
        assertThat(r.anyAvailable).isFalse()
    }

    @Test
    fun `missing metrics stay null and are not filled from other metrics`() {
        val r = context(mapOf(target to values(rhr = 52)))

        assertThat(r.restingHeartRate).isNotNull
        assertThat(r.hrv).isNull()
        assertThat(r.sleep).isNull()
        assertThat(r.bodyBattery).isNull()
        assertThat(r.stress).isNull()
    }

    @Test
    fun `thin history is passed through as INSUFFICIENT_DATA with the real current value`() {
        val r = context(history(3, values(rhr = 50)) + (target to values(rhr = 58)))

        val m = r.restingHeartRate!!.bpm
        assertThat(m.baselineStatus).isEqualTo(BaselineStatus.INSUFFICIENT_DATA)
        assertThat(m.current).isEqualTo(58.0)
        assertThat(m.sampleCount).isEqualTo(3)
        assertThat(m.baseline).isNull()
        assertThat(m.difference).isNull()
        assertThat(m.differencePercent).isNull()
    }

    @Test
    fun `a stale reading carries its age`() {
        val r = context(mapOf(target.minusDays(6) to values(sleepSeconds = 25_200)))

        assertThat(r.sleep!!.durationHours!!.ageDays).isEqualTo(6)
        assertThat(r.sleep!!.durationHours!!.date).isEqualTo(target.minusDays(6))
        assertThat(r.sleep!!.sleepScore).isNull()
    }

    @Test
    fun `the prompt carries recovery as explicit nulls, never omitted or defaulted`() {
        val prompt = ClaudeCoachPromptBuilder().createPrompt(
            CoachTestFixtures.context(recovery = context(mapOf(target to values(rhr = 52)))),
        )

        assertThat(prompt).contains("\"hrv\" : null")
        assertThat(prompt).contains("\"sleep\" : null")
        assertThat(prompt).contains("\"bodyBattery\" : null")
        assertThat(prompt).contains("\"stress\" : null")
        assertThat(prompt).contains("\"restingHeartRate\" : {")
        assertThat(prompt).contains("\"baselineStatus\" : \"INSUFFICIENT_DATA\"")
        assertThat(prompt).contains("\"baseline\" : null")
    }

    @Test
    fun `the system prompt leaves the training decision to the coach and forbids invented recovery data`() {
        val system = ClaudeCoachPromptBuilder().systemPrompt()

        assertThat(system).contains("rated them as good or bad")
        assertThat(system).contains("interpreting")
        assertThat(system).contains("ageDays")
        assertThat(system).contains("INSUFFICIENT_DATA")
        assertThat(system).contains("Only cite recovery numbers that appear in the context")
        assertThat(system).contains("Normal-looking wearable numbers never override reported pain")
    }

    @Test
    fun `the system prompt contains no double quote, because it is passed as a CLI argument`() {
        // ClaudeCliClient passes the system prompt as a --system-prompt argument. On Windows,
        // ProcessBuilder silently drops embedded double quotes from an argument (verified during
        // Phase 6F), so the model would receive different text than the source shows. The training
        // context is not affected: it travels on stdin.
        assertThat(ClaudeCoachPromptBuilder().systemPrompt()).doesNotContain("\"")
    }

    @Test
    fun `the recovery context carries no rating or recommendation field`() {
        val names = listOf(
            RecoveryContext::class.java, RecoveryMeasurement::class.java, HrvRecovery::class.java,
            SleepRecovery::class.java, RestingHeartRateRecovery::class.java, BodyBatteryRecovery::class.java,
            StressRecovery::class.java,
        ).flatMap { c -> c.declaredFields.map { it.name.lowercase() } }

        listOf("rating", "good", "bad", "recommend", "readiness", "verdict", "label", "level").forEach { word ->
            assertThat(names).noneMatch { it.contains(word) }
        }
    }
}
