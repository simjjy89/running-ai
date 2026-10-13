package com.runningai.integration.strava

import com.garmin.fit.DateTime
import com.garmin.fit.DeveloperDataIdMesg
import com.garmin.fit.DeveloperField
import com.garmin.fit.FieldDescriptionMesg
import com.garmin.fit.FileEncoder
import com.garmin.fit.FileIdMesg
import com.garmin.fit.Fit
import com.garmin.fit.FitBaseType
import com.garmin.fit.Intensity
import com.garmin.fit.LapMesg
import com.garmin.fit.LapTrigger
import com.garmin.fit.Manufacturer
import com.garmin.fit.RecordMesg
import com.garmin.fit.SessionMesg
import com.garmin.fit.Sport
import com.garmin.fit.SubSport
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Date
import java.util.zip.GZIPOutputStream

/**
 * Builds synthetic FIT activity files for the tests.
 *
 * Everything here is fabricated. No byte of the athlete's real export is committed to this repository -
 * the real archive holds genuine GPS traces - so the fixtures state the SHAPES the real files were
 * measured to have (Phase 7E-2) and nothing else:
 *
 *  - a Strava RE-ENCODE: `manufacturer = strava`, a developer field `apple_health_uuid`, laps with no
 *    intensity and no workout step index, and no heart rate at all;
 *  - a Garmin ORIGINAL: real lap intensities and `wkt_step_index`, full sensor coverage.
 */
object SyntheticFitFiles {

    const val STRAVA_PRODUCT = 1165

    /** A Strava re-encode of an Apple Health workout: dev field present, no HR, structureless laps. */
    fun stravaReEncode(
        tempDir: Path,
        startedAt: Instant,
        appleHealthUuid: String,
        recordCount: Int = 10,
        lapDurations: List<Double> = listOf(6.0, 4.0),
        sport: Sport = Sport.RUNNING,
        subSport: SubSport? = null,
        withPower: Boolean = true,
        withHeartRate: Boolean = false,
        withPosition: Boolean = true,
    ): ByteArray = build(tempDir) { encoder ->
        val devId = DeveloperDataIdMesg().apply {
            setDeveloperDataIndex(0)
            setApplicationVersion(1L)
            repeat(16) { setApplicationId(it, 0) }
        }
        val fieldDesc = FieldDescriptionMesg().apply {
            setDeveloperDataIndex(0)
            setFieldDefinitionNumber(3)
            setFitBaseTypeId(FitBaseType.STRING)
            setFieldName(0, FitActivityDecoder.APPLE_HEALTH_UUID)
        }
        encoder.write(FileIdMesg().apply {
            type = com.garmin.fit.File.ACTIVITY
            manufacturer = Manufacturer.STRAVA
            product = STRAVA_PRODUCT
            timeCreated = DateTime(Date.from(startedAt))
        })
        encoder.write(devId)
        encoder.write(fieldDesc)

        repeat(recordCount) { i ->
            encoder.write(RecordMesg().apply {
                timestamp = DateTime(Date.from(startedAt.plusSeconds(i.toLong())))
                distance = (i * 3.0).toFloat()
                enhancedSpeed = 3.0f
                if (withPower) power = 250 + i
                if (withHeartRate) heartRate = (140 + i).toShort()
                if (withPosition) {
                    // A fabricated point in the middle of the Yellow Sea, deliberately not a real place.
                    positionLat = 440000000
                    positionLong = 1240000000
                }
                enhancedAltitude = 20.0f
            })
        }
        var lapStart = startedAt
        lapDurations.forEach { seconds ->
            encoder.write(LapMesg().apply {
                startTime = DateTime(Date.from(lapStart))
                timestamp = DateTime(Date.from(lapStart.plusSeconds(seconds.toLong())))
                totalElapsedTime = seconds.toFloat()
                totalTimerTime = seconds.toFloat()
                totalDistance = (seconds * 3.0).toFloat()
                // No intensity and no wkt_step_index: measured absent on every Strava re-encode.
            })
            lapStart = lapStart.plusSeconds(seconds.toLong())
        }
        encoder.write(SessionMesg().apply {
            startTime = DateTime(Date.from(startedAt))
            timestamp = DateTime(Date.from(startedAt.plusSeconds(recordCount.toLong())))
            this.sport = sport
            if (subSport != null) this.subSport = subSport
            totalElapsedTime = lapDurations.sum().toFloat()
            totalTimerTime = lapDurations.sum().toFloat()
            totalDistance = (recordCount * 3.0).toFloat()
            totalCalories = 100
            if (withHeartRate) {
                avgHeartRate = 145
                maxHeartRate = 160
            }
            if (withPower) {
                avgPower = 255
                maxPower = 300
            }
            addDeveloperField(DeveloperField(fieldDesc, devId).apply { setValue(appleHealthUuid) })
        })
    }

    /** A Garmin device original: laps carry intensity and workout step index, sensors are complete. */
    fun garminOriginal(
        tempDir: Path,
        startedAt: Instant,
        recordCount: Int = 12,
    ): ByteArray = build(tempDir) { encoder ->
        encoder.write(FileIdMesg().apply {
            type = com.garmin.fit.File.ACTIVITY
            manufacturer = Manufacturer.GARMIN
            garminProduct = 4257
            timeCreated = DateTime(Date.from(startedAt))
        })
        repeat(recordCount) { i ->
            encoder.write(RecordMesg().apply {
                timestamp = DateTime(Date.from(startedAt.plusSeconds(i.toLong())))
                distance = (i * 3.0).toFloat()
                enhancedSpeed = 3.2f
                heartRate = (150 + i).toShort()
                cadence = 85
                fractionalCadence = 0.5f
                power = 300
            })
        }
        listOf(
            Triple(Intensity.WARMUP, 0, 4.0),
            Triple(Intensity.ACTIVE, 1, 4.0),
            Triple(Intensity.RECOVERY, 2, 4.0),
        ).forEachIndexed { index, (intensity, step, seconds) ->
            encoder.write(LapMesg().apply {
                messageIndex = index
                startTime = DateTime(Date.from(startedAt.plusSeconds((index * 4).toLong())))
                totalElapsedTime = seconds.toFloat()
                totalTimerTime = seconds.toFloat()
                totalDistance = 12.0f
                this.intensity = intensity
                lapTrigger = LapTrigger.TIME
                wktStepIndex = step
                avgHeartRate = 155
                // Real Garmin laps state their own average speed; the Strava re-encode does not,
                // which is the difference the mapper's derivation test turns on.
                enhancedAvgSpeed = 3.2f
                avgRunningCadence = 85
                avgPower = 300
            })
        }
        encoder.write(SessionMesg().apply {
            startTime = DateTime(Date.from(startedAt))
            sport = Sport.RUNNING
            subSport = SubSport.GENERIC
            totalElapsedTime = 12.0f
            totalTimerTime = 12.0f
            totalDistance = 36.0f
            avgHeartRate = 155
            maxHeartRate = 170
        })
    }

    /** Writes a Strava-shaped export directory: `activities.csv` plus `activities/<upload>.fit.gz`. */
    fun writeArchive(root: Path, rows: List<ArchiveRow>) {
        Files.createDirectories(root.resolve("activities"))
        val csv = buildString {
            // The real export repeats several column names; the reader must take the first. The
            // duplicates below are deliberate so the test exercises that.
            appendLine("Activity ID,Activity Date,Activity Name,Activity Type,Elapsed Time,Distance,Filename,Elapsed Time,Distance")
            rows.forEach { row ->
                Files.write(root.resolve("activities").resolve(row.filename), row.bytes)
                appendLine(
                    "${row.stravaActivityId},\"${row.exportDate}\",\"A name, with a comma\"," +
                        "${row.activityType},100,1000,activities/${row.filename},100,1000")
            }
        }
        Files.writeString(root.resolve(StravaArchive.ACTIVITIES_CSV), csv)
    }

    data class ArchiveRow(
        val stravaActivityId: String,
        val exportDate: String,
        val activityType: String,
        val filename: String,
        val bytes: ByteArray,
    )

    private fun build(tempDir: Path, block: (FileEncoder) -> Unit): ByteArray {
        val file = Files.createTempFile(tempDir, "synthetic", ".fit").toFile()
        val encoder = FileEncoder(file, Fit.ProtocolVersion.V2_0)
        try {
            block(encoder)
        } finally {
            encoder.close()
        }
        val raw = file.readBytes()
        val gz = ByteArrayOutputStream()
        GZIPOutputStream(gz).use { it.write(raw) }
        return gz.toByteArray()
    }
}
