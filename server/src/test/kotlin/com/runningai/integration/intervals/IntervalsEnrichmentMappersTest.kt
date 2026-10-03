package com.runningai.integration.intervals

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/**
 * Pure mapper tests against the LIVE_SHAPE.ANONYMISED fixtures (shape verified on real responses,
 * 2026-10-03). No Spring, no network.
 */
class IntervalsEnrichmentMappersTest {

    private val json = ObjectMapper()
    private val activityMapper = IntervalsActivityMapper()
    private val wellnessMapper = IntervalsWellnessMapper()

    private fun activities() = json.readTree(javaClass.getResourceAsStream(
        "/fixtures/intervals/activities-list.LIVE_SHAPE.ANONYMISED.json"))

    private fun wellness() = json.readTree(javaClass.getResourceAsStream(
        "/fixtures/intervals/wellness.LIVE_SHAPE.ANONYMISED.json"))

    @Test
    fun `maps a full live-shaped activity item`() {
        val s = activityMapper.map(activities()[0])

        assertThat(s.intervalsActivityId).isEqualTo("i00000001")
        assertThat(s.source).isEqualTo("GARMIN_CONNECT")
        assertThat(s.externalId).isEqualTo("90000000001")
        assertThat(s.type).isEqualTo("Run")
        assertThat(s.startDate).isEqualTo(Instant.parse("2026-01-09T23:00:10Z"))
        assertThat(s.elapsedTimeSeconds).isEqualTo(2783)
        assertThat(s.movingTimeSeconds).isEqualTo(2773)
        assertThat(s.distanceMeters).isEqualTo(9917.21)
        assertThat(s.trainingLoad).isEqualTo(89)
        assertThat(s.intensity).isCloseTo(107.316795, within(1e-6))
        assertThat(s.ctlAfterActivity).isCloseTo(14.982259, within(1e-6))
        assertThat(s.atlAfterActivity).isCloseTo(26.2602, within(1e-6))
        // `analyzed` carries an offset (+00:00), not a Z
        assertThat(s.sourceUpdatedAt).isEqualTo(Instant.parse("2026-01-10T00:59:01.295Z"))
    }

    @Test
    fun `missing and null fields stay null, never defaulted`() {
        val s = activityMapper.map(activities()[2])

        assertThat(s.distanceMeters).isNull()
        assertThat(s.intensity).isNull()
        assertThat(s.trainingLoad).isEqualTo(0) // a real 0 is kept: 0 is a value, null is absence
    }

    @Test
    fun `unknown additional fields are ignored`() {
        val item = activities()[0] as ObjectNode
        item.put("some_future_intervals_field", "whatever")
        assertThat(activityMapper.map(item).intervalsActivityId).isEqualTo("i00000001")
    }

    @Test
    fun `an activity without id fails the mapping`() {
        val item = (activities()[0] as ObjectNode).apply { remove("id") }
        assertThatThrownBy { activityMapper.map(item) }
            .isInstanceOf(IntervalsMappingException::class.java)
            .extracting { (it as IntervalsMappingException).code }
            .isEqualTo("INTERVALS_ACTIVITY_ID_MISSING")
    }

    @Test
    fun `a wrong-typed numeric field is contract drift and fails`() {
        val item = (activities()[0] as ObjectNode).apply { put("icu_ctl", "not-a-number") }
        assertThatThrownBy { activityMapper.map(item) }
            .isInstanceOf(IntervalsMappingException::class.java)
            .extracting { (it as IntervalsMappingException).code }
            .isEqualTo("INTERVALS_FIELD_NOT_NUMBER")
    }

    @Test
    fun `maps a full wellness day - ctl and atl are the calculated fitness and fatigue`() {
        val d = wellnessMapper.map(wellness()[0])

        assertThat(d.date).isEqualTo(LocalDate.parse("2026-01-10"))
        assertThat(d.ctl).isCloseTo(14.982259, within(1e-6))
        assertThat(d.atl).isCloseTo(26.2602, within(1e-6))
        assertThat(d.ctlLoad).isEqualTo(89.0)
        assertThat(d.atlLoad).isEqualTo(89.0)
        assertThat(d.rampRate).isCloseTo(0.37189484, within(1e-6))
        assertThat(d.sourceUpdatedAt).isEqualTo(Instant.parse("2026-01-10T15:54:30.435Z"))
        assertThat(d.derivedForm).isCloseTo(14.982259 - 26.2602, within(1e-6))
    }

    @Test
    fun `derived form needs both ctl and atl`() {
        val d = wellnessMapper.map(wellness()[1])

        assertThat(d.ctl).isNotNull()
        assertThat(d.atl).isNull()
        assertThat(d.derivedForm).isNull()
        assertThat(d.rampRate).isNull()
        assertThat(d.atlLoad).isNull()
        assertThat(d.sourceUpdatedAt).isNull()
    }

    @Test
    fun `subjective fatigue is never mapped to atl`() {
        // The third fixture day reports subjective fatigue = 4 while calculated atl = 12.9.
        val d = wellnessMapper.map(wellness()[2])

        assertThat(d.atl).isCloseTo(12.9, within(1e-9))
        assertThat(d.atl).isNotEqualTo(4.0)
        assertThat(d.derivedForm).isCloseTo(13.3 - 12.9, within(1e-9))
    }

    @Test
    fun `a wellness entry whose id is not a date fails`() {
        val entry = (wellness()[0] as ObjectNode).apply { put("id", "i1234") }
        assertThatThrownBy { wellnessMapper.map(entry) }
            .isInstanceOf(IntervalsMappingException::class.java)
            .extracting { (it as IntervalsMappingException).code }
            .isEqualTo("INTERVALS_WELLNESS_ID_NOT_DATE")
    }

    @Test
    fun `a wellness entry without id fails`() {
        val entry = (wellness()[0] as ObjectNode).apply { remove("id") }
        assertThatThrownBy { wellnessMapper.map(entry) }
            .isInstanceOf(IntervalsMappingException::class.java)
            .extracting { (it as IntervalsMappingException).code }
            .isEqualTo("INTERVALS_WELLNESS_ID_MISSING")
    }
}
