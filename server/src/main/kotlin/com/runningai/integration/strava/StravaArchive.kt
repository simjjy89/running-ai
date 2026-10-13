package com.runningai.integration.strava

import org.springframework.stereotype.Component
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/** The configured archive directory is not a usable Strava export. */
class StravaArchiveException(val code: String, message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * One row of the export's `activities.csv`, paired with the activity file it names.
 *
 * The id that matters is [stravaActivityId] - the `Activity ID` column, which is Strava's own activity
 * id and the value Intervals.icu reuses verbatim as its own id for a Strava-sourced activity. The
 * archive FILE is named after the upload id instead, which is a different number entirely (measured:
 * 0 of 94 filenames equal the activity id), so the two are never conflated.
 */
data class StravaArchiveEntry(
    val stravaActivityId: String,
    val activityTypeLabel: String,
    val startedAt: Instant,
    val filename: String,
    val path: Path,
) {
    fun readBytes(): ByteArray = Files.readAllBytes(path)
}

/**
 * Reads the athlete's own Strava data export from a local directory (Phase 7E-2).
 *
 * The archive is a FILE SOURCE, not an API: there is no Strava client anywhere in this repository and
 * none is added here. Strava's API cannot serve this data at all - Intervals.icu returns
 * `422 Cannot read Strava activities via the API` for every one of these activities - which is why the
 * export exists in the first place.
 *
 * The directory must live OUTSIDE the repository and is never written to: this class only reads.
 */
@Component
class StravaArchive {

    /**
     * Every activity the export lists, oldest first.
     *
     * A row whose file is missing from `activities/` is dropped with a counted reason rather than
     * failing the whole archive: one unreadable row must not cost the other 93.
     */
    fun entries(root: Path): StravaArchiveListing {
        val csv = root.resolve(ACTIVITIES_CSV)
        if (!Files.isRegularFile(csv)) {
            throw StravaArchiveException(
                "STRAVA_ARCHIVE_CSV_MISSING", "no $ACTIVITIES_CSV under $root")
        }
        val rows = Files.newInputStream(csv).use { input ->
            CsvReader(InputStreamReader(input, StandardCharsets.UTF_8)).readAll()
        }
        if (rows.isEmpty()) {
            throw StravaArchiveException("STRAVA_ARCHIVE_CSV_EMPTY", "$ACTIVITIES_CSV has no header")
        }
        val header = rows.first().map { it.trim().removePrefix("﻿") }
        // The export repeats several column names (Elapsed Time, Distance, ...). Only the FIRST
        // occurrence is used, and only for columns whose meaning is unambiguous.
        fun indexOf(name: String) = header.indexOfFirst { it.equals(name, ignoreCase = true) }
        val idCol = indexOf(COL_ID)
        val dateCol = indexOf(COL_DATE)
        val typeCol = indexOf(COL_TYPE)
        val fileCol = indexOf(COL_FILENAME)
        if (idCol < 0 || dateCol < 0 || typeCol < 0 || fileCol < 0) {
            throw StravaArchiveException(
                "STRAVA_ARCHIVE_CSV_SHAPE",
                "$ACTIVITIES_CSV is missing one of: $COL_ID, $COL_DATE, $COL_TYPE, $COL_FILENAME")
        }

        val entries = mutableListOf<StravaArchiveEntry>()
        var missingFile = 0
        var unparsable = 0
        rows.drop(1).forEach { row ->
            if (row.size <= maxOf(idCol, dateCol, typeCol, fileCol)) return@forEach
            val id = row[idCol].trim()
            val relative = row[fileCol].trim()
            if (id.isEmpty() || relative.isEmpty()) {
                unparsable++
                return@forEach
            }
            val startedAt = parseExportDate(row[dateCol].trim())
            if (startedAt == null) {
                unparsable++
                return@forEach
            }
            val path = root.resolve(relative.replace('\\', '/'))
            if (!Files.isRegularFile(path)) {
                missingFile++
                return@forEach
            }
            entries += StravaArchiveEntry(
                stravaActivityId = id,
                activityTypeLabel = row[typeCol].trim(),
                startedAt = startedAt,
                filename = path.fileName.toString(),
                path = path,
            )
        }
        return StravaArchiveListing(
            entries = entries.sortedBy { it.startedAt },
            rowsInCsv = rows.size - 1,
            missingFile = missingFile,
            unparsable = unparsable,
        )
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }

    companion object {
        const val ACTIVITIES_CSV: String = "activities.csv"
        private const val COL_ID = "Activity ID"
        private const val COL_DATE = "Activity Date"
        private const val COL_TYPE = "Activity Type"
        private const val COL_FILENAME = "Filename"

        // Strava writes the export date in UTC, in the account's display locale, e.g.
        // "Oct 10, 2026, 10:39:40 PM". It is only used for ordering and for the ambiguity guard -
        // the authoritative start time always comes from the FIT session message.
        private val EXPORT_DATE: DateTimeFormatter =
            DateTimeFormatter.ofPattern("MMM d, yyyy, h:mm:ss a", Locale.ENGLISH)

        fun parseExportDate(value: String): Instant? = try {
            LocalDateTime.parse(value, EXPORT_DATE).toInstant(ZoneOffset.UTC)
        } catch (e: DateTimeParseException) {
            null
        }
    }
}

/** What [StravaArchive.entries] found, including what it could not use. */
data class StravaArchiveListing(
    val entries: List<StravaArchiveEntry>,
    val rowsInCsv: Int,
    val missingFile: Int,
    val unparsable: Int,
)

/**
 * A minimal RFC 4180 reader.
 *
 * Written rather than depended on because the need is one file with quoted, embedded-newline fields,
 * and the alternative is a dependency whose only user would be this class.
 */
internal class CsvReader(private val reader: InputStreamReader) {

    fun readAll(): List<List<String>> {
        val text = reader.readText()
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                    field.append('"'); i++
                }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> { row += field.toString(); field.setLength(0) }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row += field.toString(); field.setLength(0)
                    rows += row; row = mutableListOf()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row += field.toString(); rows += row }
        return rows.filter { it.any { cell -> cell.isNotBlank() } }
    }
}
