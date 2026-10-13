package com.runningai.integration.strava

import com.fasterxml.jackson.databind.ObjectMapper
import com.runningai.activity.detail.SampleData
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The one rule that makes merging two partial exports safe (Phase 7E-2): a stored value is never
 * overwritten.
 *
 * This is not a stylistic preference. Measured over all 25 activities Phase 7E-1 imported, the
 * Intervals.icu copy carries heart rate on 100% of samples while the August Strava FITs carry none,
 * and the FIT carries far more running power than the Intervals stream. Whichever source were allowed
 * to win wholesale, real data would be destroyed.
 */
class StravaSampleEnricherTest {

    private val enricher = StravaSampleEnricher(ObjectMapper())
    private val t0: Instant = Instant.parse("2026-08-10T00:36:56Z")

    private fun storedSample(
        index: Int,
        heartRate: Double? = null,
        power: Double? = null,
        speed: Double? = null,
    ) = SampleData(
        sampleIndex = index,
        sampleTime = t0.plusSeconds(index.toLong()),
        elapsedSeconds = index.toDouble(),
        heartRate = heartRate,
        power = power,
        speed = speed,
    )

    private fun fitWith(records: List<FitRecord>) = FitActivity(
        sport = "RUNNING", subSport = null, startedAt = t0,
        elapsedTimeSeconds = 10.0, timerTimeSeconds = 10.0, distanceMeters = 100.0,
        averageHeartRateBpm = null, maxHeartRateBpm = null, averageSpeed = null, maxSpeed = null,
        averagePower = null, maxPower = null, normalizedPower = null, averageRunningCadenceRpm = null,
        calories = null, elevationGain = null, elevationLoss = null, manufacturer = "strava",
        appleHealthUuid = "U", laps = emptyList(), records = records,
    )

    private fun fitRecord(index: Int, power: Double? = null, heartRate: Double? = null) = FitRecord(
        timestamp = t0.plusSeconds(index.toLong()), distance = null, speed = null,
        heartRateBpm = heartRate, cadenceRpm = 90.0, power = power, elevation = null,
        latitude = null, longitude = null, temperature = null,
        stanceTime = null, verticalOscillation = null, stepLength = null,
    )

    @Test
    fun `fills a null metric and records where the value came from`() {
        val stored = listOf(storedSample(0, heartRate = 150.0), storedSample(1, heartRate = 151.0))
        val fit = fitWith(listOf(fitRecord(0, power = 300.0), fitRecord(1, power = 310.0)))

        val result = enricher.enrich(stored, fit)

        assertThat(result.samplesEnriched).isEqualTo(2)
        assertThat(result.samples.map { it.power }).containsExactly(300.0, 310.0)
        assertThat(result.samples.first().extraMetrics?.get("power_source")?.asText())
            .isEqualTo(StravaSampleEnricher.SOURCE)
        assertThat(result.filledByMetric).containsEntry("power", 2)
    }

    @Test
    fun `never overwrites a value the stored sample already has`() {
        val stored = listOf(storedSample(0, heartRate = 150.0, power = 999.0))
        val fit = fitWith(listOf(fitRecord(0, power = 300.0, heartRate = 1.0)))

        val result = enricher.enrich(stored, fit)

        assertThat(result.samples.single().power).isEqualTo(999.0)
        assertThat(result.samples.single().heartRate).isEqualTo(150.0)
        assertThat(result.samplesEnriched).isZero()
        assertThat(result.samples.single().extraMetrics).isNull()
    }

    @Test
    fun `heart rate only Intervals has survives a FIT that has none`() {
        val stored = (0..4).map { storedSample(it, heartRate = 150.0 + it) }
        val fit = fitWith((0..4).map { fitRecord(it, power = 300.0) })

        val result = enricher.enrich(stored, fit)

        assertThat(result.samples.map { it.heartRate }).containsExactly(150.0, 151.0, 152.0, 153.0, 154.0)
        assertThat(result.samples.map { it.power }).allMatch { it == 300.0 }
    }

    @Test
    fun `a FIT record matching no stored sample is dropped, never appended`() {
        val stored = listOf(storedSample(0), storedSample(1))
        val fit = fitWith((0..9).map { fitRecord(it, power = 300.0) })

        val result = enricher.enrich(stored, fit)

        // The stream another source defined keeps its length, so index-based figures stay meaningful.
        assertThat(result.samples).hasSize(2)
    }

    @Test
    fun `cadence is never filled because the FIT unit is one-leg rpm`() {
        val stored = listOf(storedSample(0))
        val fit = fitWith(listOf(fitRecord(0, power = 300.0)))

        val result = enricher.enrich(stored, fit)

        assertThat(result.samples.single().cadence).isNull()
        assertThat(result.filledByMetric).doesNotContainKey("cadence")
    }

    @Test
    fun `two streams whose clocks do not overlap are not merged`() {
        val fit = fitWith((0..4).map { fitRecord(it) })

        assertThat(enricher.overlaps(fit, t0, t0.plusSeconds(10))).isTrue()
        assertThat(enricher.overlaps(fit, t0.plusSeconds(3600), t0.plusSeconds(7200))).isFalse()
        assertThat(enricher.overlaps(fit, null, null)).isFalse()
    }

    @Test
    fun `an empty stored stream is returned untouched`() {
        val result = enricher.enrich(emptyList(), fitWith(listOf(fitRecord(0, power = 1.0))))

        assertThat(result.samples).isEmpty()
        assertThat(result.samplesEnriched).isZero()
    }
}
