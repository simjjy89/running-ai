package com.runningai.integration.intervals

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.runningai.activity.ActivityType
import com.runningai.activity.ExternalSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId

/**
 * The import mapper against payloads shaped like the live ones (measured 2026-10-13 over all 27
 * Intervals-only activities of the real account). Values are synthetic; the shape, the field names and
 * the +09:00 local/UTC relationship are the live contract.
 */
class IntervalsImportCandidateMapperTest {

    private val json = ObjectMapper()
    private val mapper = IntervalsImportCandidateMapper()
    private val seoul: ZoneId = ZoneId.of("Asia/Seoul")

    private fun item(
        id: String = "i180579444",
        type: String? = "Run",
        source: String = "OAUTH_CLIENT",
        startLocal: String? = "2026-08-02T09:04:39",
        startUtc: String? = "2026-08-02T00:04:39Z",
        elapsed: Int? = 317,
        moving: Int? = 316,
        recording: Int? = 316,
        distance: Double? = 520.68,
    ): ObjectNode {
        val node = json.createObjectNode()
        node.put("id", id)
        node.put("source", source)
        node.put("external_id", "29CDB71A-E1B1-432B-93C7-9C59F1A77E7D")
        node.put("strava_id", "19929243399")
        node.put("device_name", "Intervals Companion")
        if (type != null) node.put("type", type)
        if (startLocal != null) node.put("start_date_local", startLocal)
        if (startUtc != null) node.put("start_date", startUtc)
        if (elapsed != null) node.put("elapsed_time", elapsed)
        if (moving != null) node.put("moving_time", moving)
        if (recording != null) node.put("icu_recording_time", recording)
        if (distance != null) node.put("distance", distance)
        node.put("average_heartrate", 146)
        node.put("max_heartrate", 168)
        node.put("average_speed", 1.641)
        node.put("max_speed", 3.371)
        node.put("average_cadence", 63.996857)
        node.put("calories", 37)
        node.put("icu_lap_count", 0)
        node.put("icu_training_load", 4)
        node.put("analyzed", "2026-08-28T06:29:14.144+00:00")
        node.putArray("stream_types").add("time").add("cadence").add("heartrate")
        return node
    }

    @Test
    fun `the live activity shape maps field for field`() {
        val candidate = mapper.map(item())

        assertThat(candidate.intervalsActivityId).isEqualTo("i180579444")
        assertThat(candidate.source).isEqualTo("OAUTH_CLIENT")
        // the recording client's own id - a UUID, never a Garmin activity id
        assertThat(candidate.externalId).isEqualTo("29CDB71A-E1B1-432B-93C7-9C59F1A77E7D")
        assertThat(candidate.stravaActivityId).isEqualTo("19929243399")
        assertThat(candidate.startDate).isEqualTo(Instant.parse("2026-08-02T00:04:39Z"))
        assertThat(candidate.lapCount).isZero()
        assertThat(candidate.declaredStreamTypes).containsExactly("time", "cadence", "heartrate")
        assertThat(candidate.analyzedAt).isEqualTo(Instant.parse("2026-08-28T06:29:14.144Z"))
        assertThat(candidate.noSourceData).isFalse()
    }

    @Test
    fun `the stored external identity is the Intervals id, never an invented Garmin id`() {
        val normalized = mapper.toNormalizedActivity(mapper.map(item()), seoul)

        assertThat(normalized.externalSource()).isEqualTo(ExternalSource.INTERVALS_ICU)
        assertThat(normalized.externalId()).isEqualTo("i180579444")
    }

    @Test
    fun `the stored duration is the recorded time, not the elapsed time that includes standing still`() {
        // live case i180579382: 1144 s recorded, 1441 s elapsed - a 297 s stop
        val candidate = mapper.map(item(elapsed = 1441, moving = 1144, recording = 1144))

        assertThat(mapper.toNormalizedActivity(candidate, seoul).durationSeconds()).isEqualTo(1144)

        val detail = mapper.toDetail(candidate)
        assertThat(detail.elapsedDurationSeconds).isEqualTo(1441.0)
        assertThat(detail.movingDurationSeconds).isEqualTo(1144.0)
        assertThat(detail.durationSeconds).isEqualTo(1144.0)
    }

    @Test
    fun `only the live-observed type pairs map, the rest are unsupported`() {
        assertThat(IntervalsImportCandidateMapper.activityTypeOf("Run")).isEqualTo(ActivityType.RUN)
        assertThat(IntervalsImportCandidateMapper.activityTypeOf("VirtualRun")).isEqualTo(ActivityType.TREADMILL_RUN)
        assertThat(IntervalsImportCandidateMapper.activityTypeOf("VirtualRide")).isEqualTo(ActivityType.INDOOR_CYCLING)
        assertThat(IntervalsImportCandidateMapper.activityTypeOf("Ride")).isNull()
        assertThat(IntervalsImportCandidateMapper.activityTypeOf("Workout")).isNull()
    }

    @Test
    fun `an unsupported type is rejected rather than approximated`() {
        assertThatThrownBy { mapper.toNormalizedActivity(mapper.map(item(type = "Ride")), seoul) }
            .isInstanceOf(IntervalsImportValidationException::class.java)
            .extracting("code").isEqualTo("INTERVALS_UNSUPPORTED_TYPE")
    }

    @Test
    fun `a local time that is not the UTC time seen from the athlete timezone is rejected`() {
        // the payload disagrees with itself: 09:04:39 local is 00:04:39Z in Seoul, not 01:04:39Z
        val candidate = mapper.map(item(startUtc = "2026-08-02T01:04:39Z"))

        assertThatThrownBy { mapper.toNormalizedActivity(candidate, seoul) }
            .isInstanceOf(IntervalsImportValidationException::class.java)
            .extracting("code").isEqualTo("INTERVALS_TIMEZONE_MISMATCH")
    }

    @Test
    fun `a missing start instant is rejected`() {
        assertThatThrownBy { mapper.toNormalizedActivity(mapper.map(item(startUtc = null)), seoul) }
            .isInstanceOf(IntervalsImportValidationException::class.java)
            .extracting("code").isEqualTo("INTERVALS_START_DATE_MISSING")
    }

    @Test
    fun `a non-positive duration is rejected`() {
        val candidate = mapper.map(item(elapsed = 0, moving = 0, recording = 0))

        assertThatThrownBy { mapper.toNormalizedActivity(candidate, seoul) }
            .isInstanceOf(IntervalsImportValidationException::class.java)
            .extracting("code").isEqualTo("INTERVALS_DURATION_INVALID")
    }

    @Test
    fun `a negative distance is rejected`() {
        assertThatThrownBy { mapper.toNormalizedActivity(mapper.map(item(distance = -1.0)), seoul) }
            .isInstanceOf(IntervalsImportValidationException::class.java)
            .extracting("code").isEqualTo("INTERVALS_DISTANCE_INVALID")
    }

    @Test
    fun `the Strava stub is recognised as having no source data at all`() {
        // live shape of all 34 STRAVA-sourced activities
        val stub = json.createObjectNode()
        stub.put("id", "i170000001")
        stub.put("icu_athlete_id", "i692548")
        stub.put("start_date_local", "2026-07-02T07:12:00")
        stub.put("source", "STRAVA")
        stub.put("_note", "STRAVA activities are not available via the API")

        val candidate = mapper.map(stub)

        assertThat(candidate.noSourceData).isTrue()
        assertThat(candidate.type).isNull()
        assertThat(candidate.startDate).isNull()
    }

    @Test
    fun `cadence never reaches the steps-per-minute detail column`() {
        val detail = mapper.toDetail(mapper.map(item()))

        assertThat(detail.averageRunningCadenceSpm).isNull()
        assertThat(detail.maxRunningCadenceSpm).isNull()
        // Intervals training load is enrichment data; it is not Garmin's training_load column
        assertThat(detail.trainingLoad).isNull()
    }
}
