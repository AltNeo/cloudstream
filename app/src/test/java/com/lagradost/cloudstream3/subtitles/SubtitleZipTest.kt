package com.lagradost.cloudstream3.subtitles

import com.lagradost.cloudstream3.utils.SubtitleUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Ensure only actual subtitle files are extracted from subtitle ZIP archives. */
class SubtitleZipTest {

    private fun createZip(entries: List<Pair<String, String>>): File {
        val zip = File.createTempFile("test-subtitles", ".zip")
        ZipOutputStream(zip.outputStream()).use { output ->
            entries.forEach { (name, content) ->
                output.putNextEntry(ZipEntry(name))
                output.write(content.toByteArray())
                output.closeEntry()
            }
        }
        return zip
    }

    @Test
    fun `keeps only valid subtitle entries and preserves their order`() {
        val zip = createZip(
            listOf(
                "subs/" to "",
                "show/English.SRT" to "1",
                "show/notes.nfo" to "not a subtitle",
                "show/spanish.srt" to "2",
                "show/movie.mp4" to "video",
                "show/Portuguese.ASS" to "3",
            )
        )

        val result = SubtitleResource().unzip(zip)

        assertEquals(
            listOf("show/English.SRT", "show/spanish.srt", "show/Portuguese.ASS"),
            result.map { it.first }
        )
        result.forEach { (_, file) -> assertTrue("Expected temp file to exist", file.exists()) }
    }

    @Test
    fun `ignores directories and files without a subtitle extension`() {
        val zip = createZip(
            listOf(
                "Season 1/" to "",
                "Season 1/readme.txt" to "txt is a supported subtitle extension",
                "Season 1/cover.jpg" to "image",
                "Season 1/subtitles.zip" to "nested zip",
            )
        )

        val result = SubtitleResource().unzip(zip)

        assertEquals(listOf("Season 1/readme.txt"), result.map { it.first })
    }

    @Test
    fun `extension matching is case-insensitive`() {
        assertTrue(SubtitleUtils.isSubtitleFile("movie.srt"))
        assertTrue(SubtitleUtils.isSubtitleFile("movie.SRT"))
        assertTrue(SubtitleUtils.isSubtitleFile("movie.Vtt"))
        assertTrue(SubtitleUtils.isSubtitleFile("movie.TTML"))
        assertTrue(SubtitleUtils.isSubtitleFile("movie.DfXp"))

        assertFalse(SubtitleUtils.isSubtitleFile("movie.mp4"))
        assertFalse(SubtitleUtils.isSubtitleFile("movie.txt2"))
        assertFalse(SubtitleUtils.isSubtitleFile("movie"))
    }
}
