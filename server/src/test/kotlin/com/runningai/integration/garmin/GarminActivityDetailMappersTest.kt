package com.runningai.integration.garmin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.runningai.activity.detail.DetailPayloadType
import com.runningai.activity.detail.ZoneType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant

/**
 * Pure mapper tests (no Spring, no network). The fixtures carry live-verified shapes with synthetic values
 * (Phase 6H-1B); the point is the mapping rules: nothing fabricated, nothing silently dropped, descriptors
 * resolved per payload.
 */
class GarminActivityDetailMappersTest {

    private val json = ObjectMapper()
    private val detail = GarminActivityDetailMapper()
    private val laps = GarminLapMapper(json)
    private val zones = GarminZoneMapper()
    private val samples = GarminSampleMapper(json)

    private fun fixture(name: String): JsonNode =
        json.readTree(javaClass.getResourceAsStream("/fixtures/garmin/$name") ?: error("missing fixture $name"))

    private fun node(text: String): JsonNode = json.readTree(text)

    // ---- activity-level detail (from the activity-list item) ------------------------------------------

    @Test
    fun `activity detail maps the library-confirmed list keys exactly as reported`() {
        val d = detail.map(fixture("running-activity.json"))

        assertThat(d.sourcePayloadType).isEqualTo(DetailPayloadType.ACTIVITY_LIST)
        assertThat(d.durationSeconds).isEqualTo(3600.0)
        assertThat(d.elapsedDurationSeconds).isEqualTo(3650.0)
        assertThat(d.movingDurationSeconds).isEqualTo(3590.0)
        assertThat(d.distanceMeters).isEqualTo(10000.0)
        assertThat(d.averageSpeed).isEqualTo(2.777)
        assertThat(d.maxSpeed).isEqualTo(3.4)
        assertThat(d.averageHeartRateBpm).isEqualTo(155.0)
        assertThat(d.maxHeartRateBpm).isEqualTo(172.0)
        assertThat(d.averageRunningCadenceSpm).isEqualTo(172.0)
        assertThat(d.elevationGain).isEqualTo(85.0)
        assertThat(d.calories).isEqualTo(720.0)
    }

    @Test
    fun `metrics the list item does not report stay null and are never derived`() {
        val d = detail.map(fixture("running-activity.json"))

        // the fixture has no power, cadence max, training effect or load: nothing is invented
        assertThat(d.averagePower).isNull()
        assertThat(d.maxPower).isNull()
        assertThat(d.normalizedPower).isNull()
        assertThat(d.maxRunningCadenceSpm).isNull()
        assertThat(d.aerobicTrainingEffect).isNull()
        assertThat(d.anaerobicTrainingEffect).isNull()
        assertThat(d.trainingLoad).isNull()
        assertThat(d.trainingEffectLabel).isNull()
    }

    @Test
    fun `a non-numeric metric fails the mapping instead of being guessed`() {
        assertThatThrownBy { detail.map(node("""{"duration":"3600"}""")) }
            .isInstanceOfSatisfying(GarminDetailMappingException::class.java) {
                assertThat(it.code).isEqualTo("NON_NUMERIC_METRIC")
            }
    }

    // ---- laps (PROVISIONAL shape) ---------------------------------------------------------------------

    @Test
    fun `laps keep the source index, order and values, and unknown fields in extraMetrics`() {
        val result = laps.map(fixture("detail/splits.LIVE_SHAPE.ANONYMISED.json"))

        // live lapIndex is 1-based (messageIndex is the 0-based one and stays an extra metric)
        assertThat(result.map { it.lapIndex }).containsExactly(1, 2, 3, 4)
        val first = result[0]
        assertThat(first.startTime).isEqualTo(Instant.parse("2026-09-28T21:30:00Z"))
        assertThat(first.durationSeconds).isEqualTo(155.1)
        assertThat(first.distanceMeters).isEqualTo(500.0)
        assertThat(first.averageHeartRateBpm).isEqualTo(120.0)
        assertThat(first.averageCadence).isEqualTo(160.0)
        assertThat(first.extraMetrics!!.get("groundContactTime").asDouble()).isEqualTo(245.0)
        assertThat(first.extraMetrics!!.get("messageIndex").asInt()).isEqualTo(0)
        // a lap that reports less is left null, never copied from a neighbour or estimated
        val third = result[2]
        assertThat(third.maxHeartRateBpm).isNull()
        assertThat(third.elevationGain).isNull()
        assertThat(third.movingDurationSeconds).isNull()
    }

    @Test
    fun `the live interval structure survives in extraMetrics, lap by lap`() {
        val result = laps.map(fixture("detail/splits.LIVE_SHAPE.ANONYMISED.json"))

        // Garmin marks work and rest on the lap itself; RunningAI stores it rather than re-deriving it.
        assertThat(result.map { it.extraMetrics?.get("intensityType")?.asText() })
            .containsExactly("WARMUP", "ACTIVE", "RECOVERY", "COOLDOWN")
        // a lap is not a workout step: live steps repeat across laps and the last lap carries none
        assertThat(result.map { it.extraMetrics?.get("wktStepIndex")?.asInt() }).containsExactly(0, 2, 3, null)
    }

    @Test
    fun `laps without a source index use their position in the source list`() {
        val result = laps.map(node("""{"lapDTOs":[{"duration":60.0},{"duration":70.0}]}"""))

        assertThat(result.map { it.lapIndex }).containsExactly(0, 1)
        assertThat(result.map { it.extraMetrics }).containsOnlyNulls()
    }

    @Test
    fun `an empty splits payload means no laps, an unknown shape fails`() {
        assertThat(laps.map(node("{}"))).isEmpty()
        assertThat(laps.map(json.nullNode())).isEmpty()
        assertThatThrownBy { laps.map(node("""{"splits":[]}""")) }
            .isInstanceOfSatisfying(GarminDetailMappingException::class.java) {
                assertThat(it.code).isEqualTo("UNKNOWN_SPLITS_SHAPE")
            }
        assertThatThrownBy { laps.map(node("""{"lapDTOs":[{"lapIndex":1},{"lapIndex":1}]}""")) }
            .isInstanceOf(GarminDetailMappingException::class.java)
    }

    @ParameterizedTest
    @ValueSource(strings = ["2026-09-28 21:30:00", "2026-09-28T21:30:00", "2026-09-28T21:30:00.0", "2026-09-28T21:30:00Z", "2026-09-28T23:30:00+02:00"])
    fun `GMT timestamps are read as UTC in every accepted form`(text: String) {
        assertThat(parseGmt(text, "t")).isEqualTo(Instant.parse("2026-09-28T21:30:00Z"))
    }

    // ---- zones (PROVISIONAL shape) --------------------------------------------------------------------

    @Test
    fun `zones keep number, lower bound and time, and never derive an upper bound`() {
        val result = zones.map(fixture("detail/hr-zones.LIVE_SHAPE.ANONYMISED.json"), ZoneType.HEART_RATE)

        assertThat(result.map { it.zoneNumber }).containsExactly(1, 2, 3, 4, 5)
        assertThat(result.map { it.durationSeconds }).containsExactly(120.0, 600.5, 1500.0, 300.0, 0.0)
        assertThat(result.map { it.minValue }).containsExactly(98.0, 118.0, 137.0, 157.0, 176.0)
        assertThat(result.map { it.maxValue }).containsOnlyNulls()
        assertThat(result).allMatch { it.zoneType == ZoneType.HEART_RATE }
    }

    @Test
    fun `an empty zones payload means no zones, other shapes fail`() {
        assertThat(zones.map(node("{}"), ZoneType.POWER)).isEmpty()
        assertThat(zones.map(node("[]"), ZoneType.POWER)).isEmpty()
        assertThatThrownBy { zones.map(node("""{"zones":[1]}"""), ZoneType.POWER) }
            .isInstanceOf(GarminDetailMappingException::class.java)
        assertThatThrownBy { zones.map(node("""[{"secsInZone":1.0}]"""), ZoneType.POWER) }
            .isInstanceOfSatisfying(GarminDetailMappingException::class.java) {
                assertThat(it.code).isEqualTo("INVALID_ZONE_NUMBER")
            }
    }

    // ---- samples (descriptor-based) ---------------------------------------------------------------------

    @Test
    fun `samples are resolved through this payload's descriptors, not by position`() {
        // In this payload heart rate is at index 3 and the timestamp at index 7, described out of order.
        val result = samples.map(fixture("detail/samples-outdoor.LIVE_SHAPE.ANONYMISED.json"))

        assertThat(result).hasSize(3)
        assertThat(result.map { it.heartRate }).containsExactly(98.0, 104.0, 111.0)
        assertThat(result.map { it.sampleTime }).containsExactly(
            Instant.ofEpochMilli(1790000000000), Instant.ofEpochMilli(1790000004000), Instant.ofEpochMilli(1790000009000),
        )
        assertThat(result.map { it.elapsedSeconds }).containsExactly(0.0, 4.0, 9.0)
        assertThat(result.map { it.distance }).containsExactly(0.0, 11.2, 25.6)
        assertThat(result.map { it.speed }).containsExactly(0.0, 2.8, 2.9)
    }

    @Test
    fun `the same metric at a different index in another payload is still found`() {
        val payload = node(
            """{"metricDescriptors":[{"metricsIndex":0,"key":"directHeartRate"},{"metricsIndex":1,"key":"directSpeed"}],
               "activityDetailMetrics":[{"metrics":[150.0,3.1]}]}""",
        )

        val sample = samples.map(payload).single()

        assertThat(sample.heartRate).isEqualTo(150.0)
        assertThat(sample.speed).isEqualTo(3.1)
    }

    @Test
    fun `native sampling is kept - no interpolated rows, indexes follow the source`() {
        val result = samples.map(fixture("detail/samples-outdoor.LIVE_SHAPE.ANONYMISED.json"))

        // elapsed 0, 4, 9 seconds: three rows, not ten one-second rows
        assertThat(result.map { it.sampleIndex }).containsExactly(0, 1, 2)
    }

    @Test
    fun `unknown metrics are preserved by key and missing ones stay null`() {
        val result = samples.map(fixture("detail/samples-outdoor.LIVE_SHAPE.ANONYMISED.json"))

        assertThat(result[1].extraMetrics!!.get("directVerticalOscillation").asDouble()).isEqualTo(9.1)
        assertThat(result[1].extraMetrics!!.get("sumMovingDuration").asDouble()).isEqualTo(4.0)
        // sample 0 and sample 2 report null for vertical oscillation: absent, not 0 and not carried over
        assertThat(result[0].extraMetrics!!.has("directVerticalOscillation")).isFalse()
        assertThat(result[2].extraMetrics!!.has("directVerticalOscillation")).isFalse()
        // live outdoor payloads describe no air temperature at all: null, never fabricated
        assertThat(result).allMatch { it.temperature == null }
        // what this payload does describe is read into its column
        assertThat(result.map { it.power }).containsExactly(0.0, 300.0, 320.0)
        assertThat(result.map { it.cadence }).containsExactly(0.0, 170.0, 172.0)
        assertThat(result.map { it.latitude }).containsOnly(1.5)
        assertThat(result.map { it.elevation }).containsExactly(16.6, 16.4, 16.2)
    }

    @Test
    fun `an epoch-millisecond timestamp reported as a JSON float is still read exactly`() {
        // Live Garmin sends directTimestamp as 1789772410000.0, not as an integer.
        val result = samples.map(fixture("detail/samples-outdoor.LIVE_SHAPE.ANONYMISED.json"))

        assertThat(result.first().sampleTime).isEqualTo(Instant.ofEpochMilli(1790000000000L))
    }

    @Test
    fun `the same metric keys at a completely different index layout still resolve`() {
        // Same device, different activity: live treadmill and outdoor payloads share keys but no layout.
        val outdoor = samples.map(fixture("detail/samples-outdoor.LIVE_SHAPE.ANONYMISED.json"))
        val treadmill = samples.map(fixture("detail/samples-treadmill.LIVE_SHAPE.ANONYMISED.json"))

        assertThat(treadmill.map { it.heartRate }).containsExactly(88.0, 132.0)
        assertThat(treadmill.map { it.speed }).containsExactly(0.0, 3.1)
        assertThat(treadmill.map { it.cadence }).containsExactly(0.0, 174.0)
        assertThat(treadmill.last().sampleTime).isEqualTo(Instant.ofEpochMilli(1790100005000L))
        // a treadmill reports no position and no elevation; nothing is substituted for them
        assertThat(treadmill).allMatch { it.latitude == null && it.longitude == null && it.elevation == null }
        assertThat(outdoor).allMatch { it.latitude != null && it.elevation != null }
    }

    @Test
    fun `a sample shorter than the descriptors simply lacks those metrics`() {
        val payload = node(
            """{"metricDescriptors":[{"metricsIndex":0,"key":"directHeartRate"},{"metricsIndex":3,"key":"directPower"}],
               "activityDetailMetrics":[{"metrics":[140.0]}]}""",
        )

        val sample = samples.map(payload).single()

        assertThat(sample.heartRate).isEqualTo(140.0)
        assertThat(sample.power).isNull()
    }

    @Test
    fun `descriptors follow the library's skip rules`() {
        val payload = node(
            """{"metricDescriptors":[
                  {"metricsIndex":-1,"key":"directPower"},
                  {"metricsIndex":"2","key":"directCadence"},
                  {"metricsIndex":1,"key":42},
                  {"metricsIndex":0,"key":"directHeartRate"}],
               "activityDetailMetrics":[{"metrics":[150.0,999.0,888.0]}]}""",
        )

        val sample = samples.map(payload).single()

        assertThat(sample.heartRate).isEqualTo(150.0)
        assertThat(sample.power).isNull()
        assertThat(sample.extraMetrics).isNull()
    }

    @Test
    fun `ambiguous descriptors fail instead of picking one`() {
        val sameIndex = node(
            """{"metricDescriptors":[{"metricsIndex":0,"key":"directHeartRate"},{"metricsIndex":0,"key":"directSpeed"}],
               "activityDetailMetrics":[{"metrics":[1.0]}]}""",
        )
        val sameKey = node(
            """{"metricDescriptors":[{"metricsIndex":0,"key":"directHeartRate"},{"metricsIndex":1,"key":"directHeartRate"}],
               "activityDetailMetrics":[{"metrics":[1.0,2.0]}]}""",
        )

        listOf(sameIndex, sameKey).forEach { p ->
            assertThatThrownBy { samples.map(p) }
                .isInstanceOfSatisfying(GarminDetailMappingException::class.java) {
                    assertThat(it.code).isEqualTo("AMBIGUOUS_METRIC_DESCRIPTOR")
                }
        }
    }

    @Test
    fun `empty sample payloads mean no samples, metrics without descriptors fail`() {
        assertThat(samples.map(node("{}"))).isEmpty()
        assertThat(samples.map(node("""{"metricDescriptors":[],"activityDetailMetrics":[]}"""))).isEmpty()
        assertThatThrownBy { samples.map(node("""{"activityDetailMetrics":[{"metrics":[1.0]}]}""")) }
            .isInstanceOfSatisfying(GarminDetailMappingException::class.java) {
                assertThat(it.code).isEqualTo("MISSING_METRIC_DESCRIPTORS")
            }
    }

    @Test
    fun `a non-numeric value for a column metric fails rather than being coerced`() {
        val payload = node(
            """{"metricDescriptors":[{"metricsIndex":0,"key":"directHeartRate"}],
               "activityDetailMetrics":[{"metrics":["fast"]}]}""",
        )

        assertThatThrownBy { samples.map(payload) }
            .isInstanceOfSatisfying(GarminDetailMappingException::class.java) {
                assertThat(it.code).isEqualTo("NON_NUMERIC_METRIC")
            }
    }
}
