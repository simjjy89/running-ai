package com.runningai.integration.strava

import com.garmin.fit.Sport
import com.garmin.fit.SubSport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant

/**
 * The decoder against synthetic files whose shapes were measured on the real archive (Phase 7E-2).
 *
 * The two facts these tests exist to pin down are the two that the whole importer rests on: that
 * `apple_health_uuid` survives decoding (it is the exact join to Intervals.icu), and that a Strava
 * re-encode's laps genuinely carry no intensity - because that is why interval groups stay underivable
 * for those activities, and a future SDK change that started inventing a default must fail here.
 */
class FitActivityDecoderTest {

    private val decoder = FitActivityDecoder()
    private val start: Instant = Instant.parse("2026-08-10T00:36:56Z")

    @Test
    fun `reads the apple health uuid Strava stamps into the session`(@TempDir tmp: Path) {
        val uuid = "6839EC1E-EA0D-496F-B994-2FFCC6635A71"
        val fit = decoder.decodeGzipped(SyntheticFitFiles.stravaReEncode(tmp, start, uuid))

        assertThat(fit.appleHealthUuid).isEqualTo(uuid)
        assertThat(fit.manufacturer).isEqualTo("STRAVA")
        assertThat(fit.startedAt).isEqualTo(start)
    }

    @Test
    fun `a Strava re-encode states no lap intensity and no workout step`(@TempDir tmp: Path) {
        val fit = decoder.decodeGzipped(
            SyntheticFitFiles.stravaReEncode(tmp, start, "A-UUID", lapDurations = listOf(6.0, 4.0)))

        assertThat(fit.laps).hasSize(2)
        assertThat(fit.laps.map { it.intensity }).containsOnlyNulls()
        assertThat(fit.laps.map { it.workoutStepIndex }).containsOnlyNulls()
        // lapIndex is 1-based, matching the live Garmin contract recorded in Phase 6H-1B.
        assertThat(fit.laps.map { it.lapIndex }).containsExactly(1, 2)
    }

    @Test
    fun `a Garmin original keeps the intensity and step index the device recorded`(@TempDir tmp: Path) {
        val fit = decoder.decodeGzipped(SyntheticFitFiles.garminOriginal(tmp, start))

        assertThat(fit.laps.map { it.intensity }).containsExactly("WARMUP", "ACTIVE", "RECOVERY")
        assertThat(fit.laps.map { it.workoutStepIndex }).containsExactly(0, 1, 2)
        assertThat(fit.manufacturer).isEqualTo("GARMIN")
    }

    @Test
    fun `an absent channel decodes to null rather than to zero`(@TempDir tmp: Path) {
        val fit = decoder.decodeGzipped(SyntheticFitFiles.stravaReEncode(
            tmp, start, "A-UUID", withHeartRate = false, withPower = false, withPosition = false))

        assertThat(fit.records).isNotEmpty
        assertThat(fit.records.map { it.heartRateBpm }).containsOnlyNulls()
        assertThat(fit.records.map { it.power }).containsOnlyNulls()
        assertThat(fit.records.map { it.latitude }).containsOnlyNulls()
        assertThat(fit.averageHeartRateBpm).isNull()
        assertThat(fit.sensorChannels).doesNotContain("heart_rate", "power", "position")
    }

    @Test
    fun `reports which sensor channels actually carried data`(@TempDir tmp: Path) {
        val fit = decoder.decodeGzipped(SyntheticFitFiles.stravaReEncode(
            tmp, start, "A-UUID", withHeartRate = true, withPower = true, withPosition = true))

        assertThat(fit.sensorChannels).contains("heart_rate", "power", "position", "speed", "distance")
    }

    @Test
    fun `converts semicircle coordinates to degrees`(@TempDir tmp: Path) {
        val fit = decoder.decodeGzipped(SyntheticFitFiles.stravaReEncode(tmp, start, "A-UUID"))

        // 440000000 semicircles * 180 / 2^31
        assertThat(fit.records.first().latitude).isCloseTo(36.88, org.assertj.core.data.Offset.offset(0.01))
    }

    @Test
    fun `keeps the sub sport so a treadmill run is distinguishable`(@TempDir tmp: Path) {
        val fit = decoder.decodeGzipped(SyntheticFitFiles.stravaReEncode(
            tmp, start, "A-UUID", sport = Sport.RUNNING, subSport = SubSport.TREADMILL))

        assertThat(fit.sport).isEqualTo("RUNNING")
        assertThat(fit.subSport).isEqualTo("TREADMILL")
    }

    @Test
    fun `rejects a stream that is not gzip with a stable code`() {
        assertThatThrownBy { decoder.decodeGzipped(byteArrayOf(1, 2, 3, 4)) }
            .isInstanceOf(FitDecodeException::class.java)
            .extracting { (it as FitDecodeException).code }
            .isEqualTo("FIT_GZIP_UNREADABLE")
    }
}
