package com.lagradost.cloudstream3.companion.phone

import com.lagradost.cloudstream3.companion.protocol.LinkType
import com.lagradost.cloudstream3.companion.protocol.PlayRequest
import com.lagradost.cloudstream3.companion.protocol.ResolvedAudioTrack
import com.lagradost.cloudstream3.companion.protocol.ResolvedLink
import com.lagradost.cloudstream3.companion.protocol.ResolvedSubtitle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhonePayloadValidatorTest {
    @Test
    fun `rejects userinfo and unallowlisted nested headers`() {
        val base = request(
            link = ResolvedLink(
                url = "https://cdn.example/video.mp4",
                type = LinkType.VIDEO,
                quality = 720,
                sourceName = "source",
                issuedAtMs = 1,
            ),
        )
        assertFalse(
            PhonePayloadValidator.isValid(
                base.copy(links = listOf(base.links.single().copy(
                    audioTracks = listOf(
                        ResolvedAudioTrack("https://user:pass@cdn.example/audio.m4a")
                    ),
                )))
            )
        )
        assertFalse(
            PhonePayloadValidator.isValid(
                base.copy(subtitles = listOf(
                    ResolvedSubtitle(
                        url = "https://cdn.example/sub.vtt",
                        lang = "English",
                        headers = mapOf("Referer" to "https://other.example/"),
                    )
                ))
            )
        )
    }

    @Test
    fun `accepts valid nested media payload`() {
        assertTrue(
            PhonePayloadValidator.isValid(
                request(
                    link = ResolvedLink(
                        url = "https://cdn.example/video.mp4",
                        type = LinkType.VIDEO,
                        quality = 720,
                        sourceName = "source",
                        audioTracks = listOf(
                            ResolvedAudioTrack(
                                url = "https://cdn.example/audio.m4a",
                                headers = mapOf("Authorization" to "Bearer value"),
                            )
                        ),
                        issuedAtMs = 1,
                    ),
                )
            )
        )
    }

    private fun request(link: ResolvedLink) = PlayRequest(
        lineageId = "lineage",
        attempt = 0,
        links = listOf(link),
        subtitles = emptyList(),
        title = "Title",
    )
}
