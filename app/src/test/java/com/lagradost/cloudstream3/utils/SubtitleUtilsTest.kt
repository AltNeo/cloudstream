package com.lagradost.cloudstream3.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ensure the shared subtitle extension matching is reliable. */
class SubtitleUtilsTest {
    @Test
    fun `matches supported subtitle extensions case-insensitively`() {
        assertTrue(SubtitleUtils.isSubtitleFileName("movie.srt"))
        assertTrue(SubtitleUtils.isSubtitleFileName("movie.SRT"))
        assertTrue(SubtitleUtils.isSubtitleFileName("movie.Vtt"))
        assertTrue(SubtitleUtils.isSubtitleFileName("subs/movie.ASS"))
        assertTrue(SubtitleUtils.isSubtitleFileName("path/to/movie.ttml"))
        assertTrue(SubtitleUtils.isSubtitleFileName("movie.DFXP"))
        assertTrue(SubtitleUtils.isSubtitleFileName("movie.SBV"))
        assertTrue(SubtitleUtils.isSubtitleFileName("movie.TXT"))
    }

    @Test
    fun `rejects files that are clearly not subtitles`() {
        assertFalse(SubtitleUtils.isSubtitleFileName("movie.mkv"))
        assertFalse(SubtitleUtils.isSubtitleFileName("movie.mp4"))
        assertFalse(SubtitleUtils.isSubtitleFileName("cover.jpg"))
        assertFalse(SubtitleUtils.isSubtitleFileName("archive.zip"))
        assertFalse(SubtitleUtils.isSubtitleFileName("movie.srt.bak"))
        assertFalse(SubtitleUtils.isSubtitleFileName("subtitle"))
        assertFalse(SubtitleUtils.isSubtitleFileName("subs/"))
        assertFalse(SubtitleUtils.isSubtitleFileName("fake.srt/"))
    }
}
