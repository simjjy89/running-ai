package com.runningai.integration.strava

import com.fasterxml.jackson.databind.ObjectMapper
import com.garmin.fit.Sport
import com.garmin.fit.SubSport
import com.runningai.activity.ActivityType
import com.runningai.activity.ExternalSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant

/**
 * The mapping rules, especially the ones that protect the analysis engine from being fed a unit or a
 * sport it would misread (Phase 7E-2).
 */
class StravaFitMapperTest {

    private val decoder = FitActivityDecoder()
    private val mapper = StravaFitMapper(ObjectMapper())
    private val start: Instant = Instant.parse("2026-07-09T02:27:56Z")

    private fun entry(id: String = "19929243405") =
        StravaArchiveEntry(id, "Run", start, "21065785742.fit.gz", Path.of("ignored"))

    @Test
    fun `the FIT sport decides the type, not the Strava label`(@TempDir tmp: Path) {
        // The real archive labels these "Ride" while the file itself records indoor cycling.
        val fit = decoder.decodeGzipped(SyntheticFitFiles.stravaReEncode(
            tmp, start, "U", sport = Sport.CYCLING, subSport = SubSport.INDOOR_CYCLING))

        assertThat(mapper.activityTypeOf(fit)).isEqualTo(ActivityType.INDOOR_CYCLING)
    }

    @Test
    fun `a treadmill sub sport maps to TREADMILL_RUN`(@TempDir tmp: Path) {
        val fit = decoder.decodeGzipped(SyntheticFitFiles.stravaReEncode(
            tmp, start, "U", sport = Sport.RUNNING, subSport = SubSport.TREADMILL))

        assertThat(mapper.activityTypeOf(fit)).isEqualTo(ActivityType.TREADMILL_RUN)
    }

    @Test
    fun `an outdoor ride is refused rather than approximated as indoor cycling`(@TempDir tmp: Path) {
        val fit = decoder.decodeGzipped(SyntheticFitFiles.stravaReEncode(
            tmp, start, "U", sport = Sport.CYCLING, subSport = SubSport.ROAD))

        assertThat(mapper.activityTypeOf(fit)).isNull()
    }

    @Test
    fun `strength training and walking have no ActivityType and are never guessed into one`(@TempDir tmp: Path) {
        listOf(Sport.TRAINING, Sport.WALKING, Sport.FITNESS_EQUIPMENT).forEach { sport ->
            val fit = decoder.decodeGzipped(SyntheticFitFiles.stravaReEncode(tmp, start, "U", sport = sport))
            assertThat(mapper.activityTypeOf(fit)).describedAs(sport.name).isNull()
        }
    }

    @Test
    fun `mapping an unsupported sport fails with a stable code`(@TempDir tmp: Path) {
        val fit = decoder.decodeGzipped(SyntheticFitFiles.stravaReEncode(tmp, start, "U", sport = Sport.TRAINING))

        assertThatThrownBy { mapper.toNormalizedActivity(entry(), fit) }
            .isInstanceOf(StravaMappingException::class.java)
            .extracting { (it as StravaMappingException).code }
            .asString().startsWith("NO_ACTIVITY_TYPE_FOR_")
    }

    @Test
    fun `the normalised activity keeps the Strava id verbatim and never invents a Garmin one`(@TempDir tmp: Path) {
        val fit = decoder.decodeGzipped(SyntheticFitFiles.stravaReEncode(tmp, start, "U"))

        val normalized = mapper.toNormalizedActivity(entry("19929243405"), fit)

        assertThat(normalized.externalSource()).isEqualTo(ExternalSource.STRAVA)
        assertThat(normalized.externalId()).isEqualTo("19929243405")
        assertThat(normalized.startedAt()).isEqualTo(start)
    }

    @Test
    fun `cadence is never written to the spm column and the rpm source value is kept`(@TempDir tmp: Path) {
        // The Garmin original is the only shape that carries cadence at all.
        val fit = decoder.decodeGzipped(SyntheticFitFiles.garminOriginal(tmp, start))

        val samples = mapper.toSamples(fit, start)
        assertThat(samples.map { it.cadence }).containsOnlyNulls()
        assertThat(samples.first().extraMetrics?.get("cadence_rpm")?.asDouble()).isEqualTo(85.5)
        assertThat(mapper.toDetail(fit).averageRunningCadenceSpm).isNull()
        assertThat(mapper.qualityFlags(fit)).contains(StravaFitMapper.CADENCE_SOURCE_RPM_NOT_NORMALIZED)
    }

    @Test
    fun `laps pass intensity through when stated and stay null when not`(@TempDir tmp: Path) {
        val garmin = mapper.toLaps(decoder.decodeGzipped(SyntheticFitFiles.garminOriginal(tmp, start)))
        val strava = mapper.toLaps(decoder.decodeGzipped(
            SyntheticFitFiles.stravaReEncode(tmp, start, "U", lapDurations = listOf(6.0, 4.0))))

        assertThat(garmin.map { it.intensityType }).containsExactly("WARMUP", "ACTIVE", "RECOVERY")
        assertThat(garmin.map { it.workoutStepIndex }).containsExactly(0, 1, 2)
        assertThat(strava.map { it.intensityType }).containsOnlyNulls()
        assertThat(strava.map { it.workoutStepIndex }).containsOnlyNulls()
    }

    @Test
    fun `a structureless lap set is flagged so an empty interval group is explainable`(@TempDir tmp: Path) {
        val fit = decoder.decodeGzipped(
            SyntheticFitFiles.stravaReEncode(tmp, start, "U", lapDurations = listOf(6.0, 4.0)))

        assertThat(mapper.qualityFlags(fit)).contains(
            StravaFitMapper.LAP_INTENSITY_NOT_RECORDED_BY_SOURCE,
            StravaFitMapper.LAP_WORKOUT_STEP_NOT_RECORDED_BY_SOURCE,
        )
    }

    @Test
    fun `a file with no heart rate is flagged and never given an estimated one`(@TempDir tmp: Path) {
        val fit = decoder.decodeGzipped(
            SyntheticFitFiles.stravaReEncode(tmp, start, "U", withHeartRate = false))

        assertThat(mapper.qualityFlags(fit)).contains(StravaFitMapper.NO_HEART_RATE_IN_SOURCE)
        assertThat(mapper.toSamples(fit, start).map { it.heartRate }).containsOnlyNulls()
        assertThat(mapper.toNormalizedActivity(entry(), fit).averageHeartRate() as Int?).isNull()
    }

    @Test
    fun `lap speed is derived from the lap's own distance and time, and marked as derived`(@TempDir tmp: Path) {
        // A Strava re-encode states total_distance and total_timer_time per lap but no avg_speed.
        val fit = decoder.decodeGzipped(
            SyntheticFitFiles.stravaReEncode(tmp, start, "U", lapDurations = listOf(6.0, 4.0)))

        val laps = mapper.toLaps(fit)

        // The fixture writes total_distance = seconds * 3, so the identity gives exactly 3.0 m/s.
        assertThat(laps.map { it.averageSpeed }).containsExactly(3.0, 3.0)
        assertThat(laps.map { it.extraMetrics?.get("average_speed_source")?.asText() })
            .containsOnly(StravaFitMapper.DERIVED_SPEED_SOURCE)
        assertThat(mapper.qualityFlags(fit))
            .contains(StravaFitMapper.LAP_SPEED_DERIVED_FROM_DISTANCE_AND_TIME)
    }

    @Test
    fun `a speed the source states is kept and never marked as derived`(@TempDir tmp: Path) {
        val fit = decoder.decodeGzipped(SyntheticFitFiles.garminOriginal(tmp, start))

        val laps = mapper.toLaps(fit)

        assertThat(laps.map { it.extraMetrics?.get("average_speed_source") }).containsOnlyNulls()
        assertThat(mapper.qualityFlags(fit))
            .doesNotContain(StravaFitMapper.LAP_SPEED_DERIVED_FROM_DISTANCE_AND_TIME)
    }

    @Test
    fun `per-lap heart rate is left null, never averaged in from another source`(@TempDir tmp: Path) {
        val fit = decoder.decodeGzipped(
            SyntheticFitFiles.stravaReEncode(tmp, start, "U", lapDurations = listOf(6.0, 4.0)))

        assertThat(mapper.toLaps(fit).map { it.averageHeartRateBpm }).containsOnlyNulls()
        assertThat(mapper.qualityFlags(fit))
            .contains(StravaFitMapper.LAP_HEART_RATE_NOT_RECORDED_BY_SOURCE)
    }

    @Test
    fun `elapsed seconds are measured from the session start so a recording gap stays a gap`(@TempDir tmp: Path) {
        val fit = decoder.decodeGzipped(SyntheticFitFiles.stravaReEncode(tmp, start, "U", recordCount = 5))

        val samples = mapper.toSamples(fit, start)
        assertThat(samples.map { it.elapsedSeconds }).containsExactly(0.0, 1.0, 2.0, 3.0, 4.0)
    }
}
