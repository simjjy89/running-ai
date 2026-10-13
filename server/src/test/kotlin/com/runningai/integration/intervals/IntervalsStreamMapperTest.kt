package com.runningai.integration.intervals

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The stream mapper against payloads shaped exactly like the live ones (measured 2026-10-13 over all 27
 * Intervals-only activities). The numbers are synthetic; only the SHAPE is copied from the live
 * contract - stream objects with `data`/`data2`, a `time` stream carrying elapsed seconds rather than
 * indices, latitude in `data` and longitude in `data2`, and types that are present but entirely null.
 */
class IntervalsStreamMapperTest {

    private val json = ObjectMapper()
    private val mapper = IntervalsStreamMapper()
    private val start: Instant = Instant.parse("2026-08-18T01:25:03Z")

    private fun stream(type: String, data: List<Any?>, data2: List<Any?>? = null): JsonNode {
        val node = json.createObjectNode()
        node.put("type", type)
        node.putNull("name")
        val array = node.putArray("data")
        data.forEach { value ->
            when (value) {
                null -> array.addNull()
                is Int -> array.add(value)
                is Double -> array.add(value)
                else -> error("unsupported test value")
            }
        }
        if (data2 != null) {
            val second = node.putArray("data2")
            data2.forEach { value ->
                when (value) {
                    null -> second.addNull()
                    is Double -> second.add(value)
                    else -> error("unsupported test value")
                }
            }
        }
        node.put("allNull", data.all { it == null })
        return node
    }

    private fun payload(vararg streams: JsonNode): JsonNode = json.createArrayNode().addAll(streams.toList())

    @Test
    fun `sample instants come from the time stream, not from the row position`() {
        // A recording gap: the device wrote seconds 0, 1 and 5 - the 4th point is NOT at index 2.
        val body = payload(
            stream("time", listOf(0, 1, 5)),
            stream("heartrate", listOf(154, 153, 150)),
        )

        val result = mapper.map(body, start)

        assertThat(result.samples.map { it.elapsedSeconds }).containsExactly(0.0, 1.0, 5.0)
        assertThat(result.samples.map { it.sampleTime })
            .containsExactly(start, start.plusSeconds(1), start.plusSeconds(5))
        assertThat(result.spanSeconds).isEqualTo(5)
    }

    @Test
    fun `latlng puts latitude in data and longitude in data2`() {
        // Synthetic coordinates: no real activity position ever goes into a fixture.
        val body = payload(
            stream("time", listOf(0, 1)),
            stream("latlng", listOf(11.0, 11.5), data2 = listOf(22.0, 22.5)),
        )

        val result = mapper.map(body, start)

        assertThat(result.samples[0].latitude).isEqualTo(11.0)
        assertThat(result.samples[0].longitude).isEqualTo(22.0)
        assertThat(result.samples[1].latitude).isEqualTo(11.5)
        assertThat(result.samples[1].longitude).isEqualTo(22.5)
    }

    @Test
    fun `cadence stays in extra metrics because the source unit is rpm, not the column's spm`() {
        val body = payload(
            stream("time", listOf(0, 1)),
            stream("cadence", listOf(92, 91)),
        )

        val result = mapper.map(body, start)

        assertThat(result.samples.map { it.cadence }).containsOnlyNulls()
        assertThat(result.samples[0].extraMetrics?.get("cadence")?.asInt()).isEqualTo(92)
        // and the reason is recorded, so a reader never has to wonder why the column is empty
        assertThat(result.qualityFlags).contains(IntervalsStreamMapper.CADENCE_SOURCE_RPM)
    }

    @Test
    fun `measurements whose unit matches the column go into the column`() {
        val body = payload(
            stream("time", listOf(0, 1)),
            stream("distance", listOf(0.0, 2.4)),
            stream("velocity_smooth", listOf(2.367, 2.401)),
            stream("heartrate", listOf(154, 155)),
            stream("watts", listOf(156, 158)),
            stream("altitude", listOf(12.4, 12.5)),
        )

        val result = mapper.map(body, start)

        val first = result.samples[0]
        assertThat(first.distance).isEqualTo(0.0)
        assertThat(first.speed).isEqualTo(2.367)
        assertThat(first.heartRate).isEqualTo(154.0)
        assertThat(first.power).isEqualTo(156.0)
        assertThat(first.elevation).isEqualTo(12.4)
        assertThat(first.temperature).isNull()
    }

    @Test
    fun `running dynamics have no column and are kept unconverted under their source keys`() {
        val body = payload(
            stream("time", listOf(0)),
            // live units: ms, mm, mm - stored exactly as reported
            stream("stance_time", listOf(291.0)),
            stream("vertical_oscillation", listOf(81.0)),
            stream("step_length", listOf(860.0)),
            stream("torque", listOf(17)),
            stream("fixed_altitude", listOf(10.0)),
        )

        val result = mapper.map(body, start)

        val extras = result.samples[0].extraMetrics!!
        assertThat(extras.get("stance_time").asDouble()).isEqualTo(291.0)
        assertThat(extras.get("vertical_oscillation").asDouble()).isEqualTo(81.0)
        assertThat(extras.get("step_length").asDouble()).isEqualTo(860.0)
        assertThat(extras.get("torque").asInt()).isEqualTo(17)
        assertThat(extras.get("fixed_altitude").asDouble()).isEqualTo(10.0)
    }

    @Test
    fun `a null measurement stays null and is never filled in`() {
        val body = payload(
            stream("time", listOf(0, 1)),
            stream("watts", listOf(null, 158)),
        )

        val result = mapper.map(body, start)

        assertThat(result.samples[0].power).isNull()
        assertThat(result.samples[0].extraMetrics).isNull()
        assertThat(result.samples[1].power).isEqualTo(158.0)
    }

    @Test
    fun `a stream the source recorded as entirely empty is reported, not silently dropped`() {
        val body = payload(
            stream("time", listOf(0, 1)),
            stream("watts", listOf(null, null)),
        )

        val result = mapper.map(body, start)

        assertThat(result.qualityFlags).anyMatch { it.startsWith(IntervalsStreamMapper.ALL_NULL_STREAMS) }
        assertThat(result.importedTypes).contains("watts")
    }

    @Test
    fun `missing and unknown stream types are both reported`() {
        val body = payload(
            stream("time", listOf(0)),
            stream("heartrate", listOf(150)),
            stream("core_temperature", listOf(37.1)),
        )

        val result = mapper.map(body, start)

        assertThat(result.availableTypes).containsExactly("time", "heartrate", "core_temperature")
        assertThat(result.importedTypes).containsExactly("heartrate")
        assertThat(result.unknownTypes).containsExactly("core_temperature")
        assertThat(result.missingTypes).contains("cadence", "distance", "velocity_smooth", "step_length")
    }

    @Test
    fun `streams of different lengths are rejected instead of truncated`() {
        val body = payload(
            stream("time", listOf(0, 1, 2)),
            stream("heartrate", listOf(150, 151)),
        )

        assertThatThrownBy { mapper.map(body, start) }
            .isInstanceOf(IntervalsMappingException::class.java)
            .hasMessageContaining("different lengths")
    }

    @Test
    fun `an empty payload yields no samples and says so`() {
        val result = mapper.map(json.createArrayNode(), start)

        assertThat(result.samples).isEmpty()
        assertThat(result.qualityFlags).containsExactly(IntervalsStreamMapper.NO_STREAMS)
    }

    @Test
    fun `a payload that is not an array is contract drift`() {
        assertThatThrownBy { mapper.map(json.createObjectNode(), start) }
            .isInstanceOf(IntervalsMappingException::class.java)
    }
}
