package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.utils.SubtitleUtils.isSubtitleFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the predicate used to filter ZIP entries when loading subtitles
 * from an archive ([com.lagradost.cloudstream3.subtitles.SubtitleResource]).
 * Only actual subtitle files must be exposed to the player.
 */
class SubtitleZipFilterTest {
    @Test
    fun `accepts all known subtitle extensions`() {
        listOf(
            "movie.en.srt",
            "movie.en.vtt",
            "movie.en.txt",
            "movie.en.ass",
            "movie.en.ttml",
            "movie.en.sbv",
            "movie.en.dfxp",
        ).forEach { name ->
            assertTrue("Expected subtitle file: $name", isSubtitleFile(name))
        }
    }

    @Test
    fun `extension matching is case-insensitive`() {
        listOf(
            "movie.SRT",
            "movie.VTT",
            "movie.Ass",
            "movie.SBV",
            "SUBS/MOVIE.TTML",
        ).forEach { name ->
            assertTrue("Expected subtitle file: $name", isSubtitleFile(name))
        }
    }

    @Test
    fun `preserves nested archive paths with subtitle extensions`() {
        assertTrue(isSubtitleFile("subs/eng/movie.en.srt"))
    }

    @Test
    fun `rejects files that are clearly not subtitles`() {
        listOf(
            "movie.mp4",
            "cover.jpg",
            "readme.nfo",
            "archive.zip",
            "movie.srt.bak",
            "noextension",
            "",
        ).forEach { name ->
            assertFalse("Expected non-subtitle file: $name", isSubtitleFile(name))
        }
    }

    @Test
    fun `rejects directory entries`() {
        listOf(
            "subs/",
            "subs",
            "eng/",
        ).forEach { name ->
            assertFalse("Expected directory to be rejected: $name", isSubtitleFile(name))
        }
    }
}
