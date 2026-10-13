package com.runningai.integration.strava

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** Reading the export directory, including the shapes the real `activities.csv` actually has. */
class StravaArchiveTest {

    private val archive = StravaArchive()

    private fun row(id: String, date: String, type: String = "Run", file: String = "$id.fit.gz") =
        SyntheticFitFiles.ArchiveRow(id, date, type, file, byteArrayOf(1, 2, 3))

    @Test
    fun `lists entries oldest first and resolves each file`(@TempDir tmp: Path) {
        SyntheticFitFiles.writeArchive(tmp, listOf(
            row("3", "Oct 10, 2026, 10:39:40 PM"),
            row("1", "Jun 2, 2026, 3:34:23 AM"),
            row("2", "Aug 10, 2026, 12:36:56 AM"),
        ))

        val listing = archive.entries(tmp)

        assertThat(listing.entries.map { it.stravaActivityId }).containsExactly("1", "2", "3")
        assertThat(listing.rowsInCsv).isEqualTo(3)
        assertThat(listing.entries.first().startedAt).isEqualTo(Instant.parse("2026-06-02T03:34:23Z"))
    }

    @Test
    fun `the Activity ID is kept, never the upload id the file is named after`(@TempDir tmp: Path) {
        // Measured on the real export: 0 of 94 filenames equal the activity id.
        SyntheticFitFiles.writeArchive(tmp, listOf(
            row("19929243577", "Aug 10, 2026, 12:36:56 AM", file = "21065785771.fit.gz")))

        val entry = archive.entries(tmp).entries.single()

        assertThat(entry.stravaActivityId).isEqualTo("19929243577")
        assertThat(entry.filename).isEqualTo("21065785771.fit.gz")
    }

    @Test
    fun `quoted fields with commas and duplicated column names are handled`(@TempDir tmp: Path) {
        SyntheticFitFiles.writeArchive(tmp, listOf(row("1", "Jul 2, 2026, 3:11:37 AM")))

        val entry = archive.entries(tmp).entries.single()

        assertThat(entry.activityTypeLabel).isEqualTo("Run")
        assertThat(entry.stravaActivityId).isEqualTo("1")
    }

    @Test
    fun `a row whose file is missing is counted, not fatal`(@TempDir tmp: Path) {
        SyntheticFitFiles.writeArchive(tmp, listOf(
            row("1", "Jul 2, 2026, 3:11:37 AM"),
            row("2", "Jul 3, 2026, 3:11:37 AM"),
        ))
        Files.delete(tmp.resolve("activities").resolve("2.fit.gz"))

        val listing = archive.entries(tmp)

        assertThat(listing.entries).hasSize(1)
        assertThat(listing.missingFile).isEqualTo(1)
    }

    @Test
    fun `an unparsable date is counted, not fatal`(@TempDir tmp: Path) {
        SyntheticFitFiles.writeArchive(tmp, listOf(
            row("1", "Jul 2, 2026, 3:11:37 AM"),
            row("2", "not a date"),
        ))

        val listing = archive.entries(tmp)

        assertThat(listing.entries).hasSize(1)
        assertThat(listing.unparsable).isEqualTo(1)
    }

    @Test
    fun `a directory without activities-csv is refused with a stable code`(@TempDir tmp: Path) {
        assertThatThrownBy { archive.entries(tmp) }
            .isInstanceOf(StravaArchiveException::class.java)
            .extracting { (it as StravaArchiveException).code }
            .isEqualTo("STRAVA_ARCHIVE_CSV_MISSING")
    }

    @Test
    fun `sha256 identifies the exact bytes an outcome was decided from`() {
        assertThat(archive.sha256("abc".toByteArray()))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }
}
