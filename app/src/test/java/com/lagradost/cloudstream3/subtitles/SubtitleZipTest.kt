package com.lagradost.cloudstream3.subtitles

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Ensure that loading subtitles from a ZIP archive only exposes actual subtitle files,
 * without leaking temporary files for entries that are ignored.
 */
class SubtitleZipTest {
    private val testFiles = mutableListOf<File>()

    /**
     * Unique marker embedded in every entry of the zips created by this test, so temporary
     * files created by this run can be told apart from files of concurrent test runs.
     */
    private val marker = "subtitlezip-${UUID.randomUUID()}"

    private fun createZip(vararg entries: Pair<String, String>): File {
        val file = File.createTempFile("subtitle-zip-test", ".zip").also { testFiles.add(it) }
        ZipOutputStream(file.outputStream()).use { zipStream ->
            for ((name, content) in entries) {
                zipStream.putNextEntry(ZipEntry(name))
                zipStream.write(content.toByteArray())
                zipStream.closeEntry()
            }
        }
        return file
    }

    private fun extractedTempFiles(): List<File> {
        val dir = File(
            System.getProperty("java.io.tmpdir") ?: error("java.io.tmpdir is not set")
        )
        return dir.listFiles { file ->
            file.isFile && file.name.startsWith("unzipped-subtitle")
        }?.toList() ?: emptyList()
    }

    @After
    fun cleanup() {
        testFiles.forEach { it.deleteRecursively() }
        testFiles.clear()
    }

    @Test
    fun `only subtitle entries are extracted preserving names and order`() {
        val zip = createZip(
            "subs/" to "$marker directory",
            "subs/movie.en.srt" to "$marker srt",
            "cover.jpg" to "$marker jpg",
            "subs/movie.ru.VTT" to "$marker vtt",
            "video.mkv" to "$marker mkv",
            "movie.ENG.SRT" to "$marker upper srt",
            "subs/nested/" to "$marker directory",
            "subs/nested/movie.fr.ASS" to "$marker ass",
            "Thumbs.db" to "$marker db",
        )

        val entries = SubtitleResource().unzip(zip)
        entries.forEach { testFiles.add(it.second) }

        assertEquals(
            listOf(
                "subs/movie.en.srt",
                "subs/movie.ru.VTT",
                "movie.ENG.SRT",
                "subs/nested/movie.fr.ASS",
            ),
            entries.map { it.first }
        )
        assertEquals(
            listOf(
                "$marker srt",
                "$marker vtt",
                "$marker upper srt",
                "$marker ass",
            ),
            entries.map { it.second.readText() }
        )
        entries.forEach { assertTrue(it.second.exists()) }
    }

    @Test
    fun `no temporary files are kept for ignored zip entries`() {
        val zip = createZip(
            "subs/" to "$marker directory",
            "subs/movie.srt" to "$marker srt",
            "cover.jpg" to "$marker jpg",
            "readme.md" to "$marker md",
        )

        val entries = SubtitleResource().unzip(zip)
        entries.forEach { testFiles.add(it.second) }

        assertEquals(1, entries.size)

        // Every temporary file holding content of this test's zip must be a returned subtitle
        // file, so ignored entries never leave temporary files behind.
        val extractedWithMarker = extractedTempFiles().filter { file ->
            runCatching { file.readText().contains(marker) }.getOrDefault(false)
        }
        assertEquals(
            entries.map { it.second.canonicalFile }.toSet(),
            extractedWithMarker.map { it.canonicalFile }.toSet()
        )
    }
}
