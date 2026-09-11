package com.lagradost.cloudstream3.companion.tv

import com.lagradost.cloudstream3.companion.protocol.LinkType
import com.lagradost.cloudstream3.companion.protocol.PlayRequest
import com.lagradost.cloudstream3.companion.protocol.PlaylistPart
import com.lagradost.cloudstream3.companion.protocol.ResolvedAudioTrack
import com.lagradost.cloudstream3.companion.protocol.ResolvedLink
import com.lagradost.cloudstream3.companion.protocol.ResolvedSubtitle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayRequestValidatorTest {
    @Test
    fun `rejects userinfo and fragments in every received URL`() {
        assertFalse(request(linkUrl = "https://user:pass@example.test/video").isValidForCompanion())
        assertFalse(request(linkUrl = "https://example.test/video#fragment").isValidForCompanion())
        assertFalse(
            request(
                link = ResolvedLink(
                    url = "https://example.test/video",
                    type = LinkType.VIDEO,
                    quality = 1,
                    sourceName = "source",
                    audioTracks = listOf(
                        ResolvedAudioTrack("https://example.test/audio#fragment"),
                    ),
                    issuedAtMs = 0L,
                ),
            ).isValidForCompanion(),
        )
        assertFalse(
            request(
                link = ResolvedLink(
                    url = "https://example.test/video",
                    type = LinkType.M3U8,
                    quality = 1,
                    sourceName = "source",
                    playlist = listOf(PlaylistPart("https://user:pass@example.test/part", 1L)),
                    issuedAtMs = 0L,
                ),
            ).isValidForCompanion(),
        )
        assertFalse(
            request(
                subtitles = listOf(
                    ResolvedSubtitle("https://example.test/subtitle#fragment", "English"),
                ),
            ).isValidForCompanion(),
        )
        assertTrue(request().isValidForCompanion())
    }

    private fun request(
        linkUrl: String = "https://example.test/video",
        link: ResolvedLink? = null,
        subtitles: List<ResolvedSubtitle> = emptyList(),
    ): PlayRequest = PlayRequest(
        lineageId = "lineage",
        attempt = 0,
        links = listOf(link ?: ResolvedLink(
            url = linkUrl,
            type = LinkType.VIDEO,
            quality = 1,
            sourceName = "source",
            issuedAtMs = 0L,
        )),
        subtitles = subtitles,
        title = "Title",
    )
}
