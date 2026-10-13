package com.runningai.integration.strava

import com.garmin.fit.DateTime
import com.garmin.fit.Decode
import com.garmin.fit.DeveloperFieldDescriptionListener
import com.garmin.fit.FileIdMesg
import com.garmin.fit.LapMesg
import com.garmin.fit.Manufacturer
import com.garmin.fit.Mesg
import com.garmin.fit.MesgListener
import com.garmin.fit.MesgNum
import com.garmin.fit.RecordMesg
import com.garmin.fit.SessionMesg
import org.springframework.stereotype.Component
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.Instant
import java.util.zip.GZIPInputStream

/** A FIT file could not be decoded. [code] is a stable machine code, never a library message. */
class FitDecodeException(val code: String, message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * One decoded FIT activity, in RunningAI's own vocabulary.
 *
 * Deliberately NOT a Garmin type: FIT field names and units stop at this file, exactly as Garmin's and
 * Intervals' do at their own mappers. Everything here is already in the repository's units - metres,
 * seconds, m/s, watts, bpm, degrees - and anything the source did not record is null, never a default
 * and never an estimate.
 */
data class FitActivity(
    val sport: String?,
    val subSport: String?,
    val startedAt: Instant?,
    val elapsedTimeSeconds: Double?,
    val timerTimeSeconds: Double?,
    val distanceMeters: Double?,
    val averageHeartRateBpm: Double?,
    val maxHeartRateBpm: Double?,
    val averageSpeed: Double?,
    val maxSpeed: Double?,
    val averagePower: Double?,
    val maxPower: Double?,
    val normalizedPower: Double?,
    val averageRunningCadenceRpm: Double?,
    val calories: Double?,
    val elevationGain: Double?,
    val elevationLoss: Double?,
    val manufacturer: String?,
    /** Strava stamps the originating Apple Health workout id here; it is the exact join to Intervals. */
    val appleHealthUuid: String?,
    val laps: List<FitLap>,
    val records: List<FitRecord>,
) {
    /** Which sensor channels actually carried a value. Reported from the data, never from the type. */
    val sensorChannels: List<String>
        get() = buildList {
            if (records.any { it.heartRateBpm != null }) add("heart_rate")
            if (records.any { it.cadenceRpm != null }) add("cadence")
            if (records.any { it.power != null }) add("power")
            if (records.any { it.speed != null }) add("speed")
            if (records.any { it.distance != null }) add("distance")
            if (records.any { it.latitude != null }) add("position")
            if (records.any { it.elevation != null }) add("altitude")
            if (records.any { it.stanceTime != null }) add("running_dynamics")
            if (records.any { it.temperature != null }) add("temperature")
        }
}

/**
 * One lap exactly as the file records it.
 *
 * [intensity] and [workoutStepIndex] are the device's own structural statement and are frequently
 * absent: a Strava re-encode of an Apple Health workout carries neither, because the watch recorded
 * manual lap presses and no programmed workout. They stay null in that case - the analysis engine
 * reads interval structure, it never infers it from pace.
 */
data class FitLap(
    val lapIndex: Int,
    val startTime: Instant?,
    val elapsedTimeSeconds: Double?,
    val timerTimeSeconds: Double?,
    val distanceMeters: Double?,
    val averageSpeed: Double?,
    val maxSpeed: Double?,
    val averageHeartRateBpm: Double?,
    val maxHeartRateBpm: Double?,
    val averageCadenceRpm: Double?,
    val averagePower: Double?,
    val maxPower: Double?,
    val calories: Double?,
    val intensity: String?,
    val lapTrigger: String?,
    val workoutStepIndex: Int?,
)

/** One record message. Units are already RunningAI's; an absent channel is null. */
data class FitRecord(
    val timestamp: Instant?,
    val distance: Double?,
    val speed: Double?,
    val heartRateBpm: Double?,
    /** FIT running cadence is ONE-LEG rpm, not steps per minute. Kept in the source unit on purpose. */
    val cadenceRpm: Double?,
    val power: Double?,
    val elevation: Double?,
    val latitude: Double?,
    val longitude: Double?,
    val temperature: Double?,
    val stanceTime: Double?,
    val verticalOscillation: Double?,
    val stepLength: Double?,
)

/**
 * Decodes a FIT activity file (optionally gzipped) with the format owner's own SDK (Phase 7E-2).
 *
 * FIT is a profile-driven binary format: the meaning, scale and unit of a field depend on a
 * version-specific message profile, so it cannot be read correctly by ad-hoc byte parsing. The Garmin
 * FIT SDK is therefore a dependency rather than a convenience, and it is used for DECODING ONLY -
 * nothing in this repository writes a FIT file.
 *
 * The decoder is pure: beyond the stream it is handed it performs no I/O, touches no database and makes
 * no network call, so it is unit-testable against a synthetic file.
 */
@Component
class FitActivityDecoder {

    fun decodeGzipped(bytes: ByteArray): FitActivity =
        try {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { decode(it) }
        } catch (e: FitDecodeException) {
            throw e
        } catch (e: Exception) {
            throw FitDecodeException("FIT_GZIP_UNREADABLE", "gzip stream could not be read", e)
        }

    fun decode(input: InputStream): FitActivity {
        var fileId: FileIdMesg? = null
        var session: SessionMesg? = null
        val laps = mutableListOf<LapMesg>()
        val records = mutableListOf<RecordMesg>()

        val decoder = Decode()
        // Developer field definitions arrive as their own messages. Without registering a listener the
        // SDK discards them, and apple_health_uuid - the exact join from this archive to Intervals.icu -
        // would never become readable on the session message.
        decoder.addListener(DeveloperFieldDescriptionListener { })
        decoder.addListener(MesgListener { mesg: Mesg ->
            when (mesg.num) {
                MesgNum.FILE_ID -> if (fileId == null) fileId = FileIdMesg(mesg)
                MesgNum.SESSION -> if (session == null) session = SessionMesg(mesg)
                MesgNum.LAP -> laps += LapMesg(mesg)
                MesgNum.RECORD -> records += RecordMesg(mesg)
                else -> Unit
            }
        })
        try {
            decoder.read(BufferedInputStream(input))
        } catch (e: Exception) {
            throw FitDecodeException("FIT_DECODE_FAILED", "FIT stream could not be decoded", e)
        }

        val s = session ?: throw FitDecodeException("FIT_NO_SESSION", "FIT file carries no session message")

        return FitActivity(
            sport = s.sport?.name,
            subSport = s.subSport?.name,
            startedAt = s.startTime?.toInstant(),
            elapsedTimeSeconds = s.totalElapsedTime?.toDouble(),
            timerTimeSeconds = s.totalTimerTime?.toDouble(),
            distanceMeters = s.totalDistance?.toDouble(),
            averageHeartRateBpm = s.avgHeartRate?.toDouble(),
            maxHeartRateBpm = s.maxHeartRate?.toDouble(),
            averageSpeed = (s.enhancedAvgSpeed ?: s.avgSpeed)?.toDouble(),
            maxSpeed = (s.enhancedMaxSpeed ?: s.maxSpeed)?.toDouble(),
            averagePower = s.avgPower?.toDouble(),
            maxPower = s.maxPower?.toDouble(),
            normalizedPower = s.normalizedPower?.toDouble(),
            averageRunningCadenceRpm = (s.avgRunningCadence ?: s.avgCadence)?.toDouble(),
            calories = s.totalCalories?.toDouble(),
            elevationGain = s.totalAscent?.toDouble(),
            elevationLoss = s.totalDescent?.toDouble(),
            manufacturer = fileId?.manufacturer?.let { Manufacturer.getStringFromValue(it) },
            appleHealthUuid = developerFieldNamed(s, APPLE_HEALTH_UUID),
            // lapIndex is 1-based to match the live Garmin lap contract recorded in Phase 6H-1B.
            laps = laps.mapIndexed { i, lap -> lap.toFitLap(i + 1) },
            records = records.map { it.toFitRecord() },
        )
    }

    private fun developerFieldNamed(mesg: Mesg, name: String): String? =
        mesg.developerFields
            .firstOrNull { it.name == name }
            ?.getValue(0)
            ?.toString()
            ?.ifBlank { null }

    private fun LapMesg.toFitLap(index: Int) = FitLap(
        lapIndex = index,
        startTime = startTime?.toInstant(),
        elapsedTimeSeconds = totalElapsedTime?.toDouble(),
        timerTimeSeconds = totalTimerTime?.toDouble(),
        distanceMeters = totalDistance?.toDouble(),
        averageSpeed = (enhancedAvgSpeed ?: avgSpeed)?.toDouble(),
        maxSpeed = (enhancedMaxSpeed ?: maxSpeed)?.toDouble(),
        averageHeartRateBpm = avgHeartRate?.toDouble(),
        maxHeartRateBpm = maxHeartRate?.toDouble(),
        averageCadenceRpm = (avgRunningCadence ?: avgCadence)?.toDouble(),
        averagePower = avgPower?.toDouble(),
        maxPower = maxPower?.toDouble(),
        calories = totalCalories?.toDouble(),
        intensity = intensity?.name,
        lapTrigger = lapTrigger?.name,
        workoutStepIndex = wktStepIndex,
    )

    private fun RecordMesg.toFitRecord() = FitRecord(
        timestamp = timestamp?.toInstant(),
        distance = distance?.toDouble(),
        speed = (enhancedSpeed ?: speed)?.toDouble(),
        heartRateBpm = heartRate?.toDouble(),
        cadenceRpm = cadence?.toDouble()?.plus(fractionalCadence?.toDouble() ?: 0.0),
        power = power?.toDouble(),
        elevation = (enhancedAltitude ?: altitude)?.toDouble(),
        latitude = positionLat?.semicirclesToDegrees(),
        longitude = positionLong?.semicirclesToDegrees(),
        temperature = temperature?.toDouble(),
        stanceTime = stanceTime?.toDouble(),
        verticalOscillation = verticalOscillation?.toDouble(),
        stepLength = stepLength?.toDouble(),
    )

    companion object {
        /** The developer field Strava writes when it re-encodes an Apple Health workout. */
        const val APPLE_HEALTH_UUID: String = "apple_health_uuid"

        /** FIT stores coordinates as semicircles: degrees = semicircles * 180 / 2^31. */
        private fun Int.semicirclesToDegrees(): Double = this * (180.0 / 2147483648.0)

        private fun DateTime.toInstant(): Instant = date.toInstant()
    }
}
