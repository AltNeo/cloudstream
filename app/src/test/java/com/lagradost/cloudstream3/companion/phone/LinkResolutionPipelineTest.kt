package com.lagradost.cloudstream3.companion.phone

import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newAudioFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkPlayList
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.PlayListItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkResolutionPipelineTest {
    @Test
    fun `direct links are probed and headers are allowlisted and merged`() = runBlocking {
        val probe = RecordingProbe {
            ProbeResponse(206, it, "video/mp4", byteArrayOf(1))
        }
        val clock = FixedClock(1_700_000_000_000)
        val link = ExtractorLink(
            source = "source",
            name = "name",
            url = "https://cdn.example/video.mp4?expires=1700000100",
            referer = "https://site.example/",
            quality = 720,
            headers = mapOf(
                "Cookie" to "provider=cookie",
                "X-Token" to "provider-token",
                "Not-Allowlisted" to "not-allowlisted",
            ),
            type = ExtractorLinkType.VIDEO,
        )
        val outcome = LinkResolutionPipeline(
            probe,
            clock,
            object : CompanionHeaderProvider {
                override fun webViewUserAgent() = "WebView UA"
                override fun cookiesFor(url: String) = if (url.contains("cdn")) "session=web" else null
            },
        ).resolve(
            LinkResolutionInput(
                links = listOf(link),
                subtitles = listOf(newSubtitleFile("English", "https://cdn.example/en.vtt")),
                title = "Title",
                lineageId = "lineage",
            )
        )

        val request = (outcome as LinkResolutionOutcome.Success).request
        assertEquals(1, request.links.size)
        assertEquals(1_700_000_100_000, request.links.single().expiresAtMs)
        // The source referer is cross-origin from the CDN URL, but referer-gated streams
        // require it, so it is preserved as an explicit field.
        assertEquals("https://site.example/", request.links.single().referer)
        assertEquals("WebView UA", probe.requests.single().headers["User-Agent"])
        assertEquals("provider=cookie; session=web", probe.requests.single().headers["Cookie"])
        assertFalse(probe.requests.single().headers.keys.any { it.equals("Not-Allowlisted", true) })
    }

    @Test
    fun `playlist probes first part and two additional parts preserving durations`() = runBlocking {
        val probe = RecordingProbe {
            ProbeResponse(206, "$it-final", "video/mp4", byteArrayOf(1))
        }
        val link = ExtractorLinkPlayList(
            source = "source",
            name = "name",
            playlist = listOf(
                PlayListItem("https://cdn.example/one.mp4", 11),
                PlayListItem("https://cdn.example/two.mp4", 22),
                PlayListItem("https://cdn.example/three.mp4", 33),
                PlayListItem("https://cdn.example/four.mp4", 44),
            ),
            referer = "",
            quality = 1080,
            type = ExtractorLinkType.VIDEO,
        )
        val outcome = LinkResolutionPipeline(probe, FixedClock(1000)).resolve(
            LinkResolutionInput(listOf(link), emptyList(), "Title", lineageId = "lineage")
        )
        val resolved = (outcome as LinkResolutionOutcome.Success).request.links.single()
        assertEquals(listOf(11L, 22L, 33L, 44L), resolved.playlist!!.map { it.durationUs })
        assertEquals("https://cdn.example/four.mp4", resolved.playlist[3].url)
        assertEquals(3, probe.requests.size)
        assertEquals(null, probe.requests[0].range)
        assertEquals("bytes=0-1023", probe.requests[1].range)
    }

    @Test
    fun `hls requires playlist and first segment`() = runBlocking {
        val probe = RecordingProbe { url ->
            when {
                url.endsWith("master.m3u8") -> ProbeResponse(
                    200,
                    url,
                    "application/vnd.apple.mpegurl",
                    "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nmedia.m3u8\n".toByteArray(),
                )
                url.endsWith("media.m3u8") -> ProbeResponse(
                    200,
                    url,
                    "application/vnd.apple.mpegurl",
                    "#EXTM3U\n#EXTINF:1,\nsegment.ts\n".toByteArray(),
                )
                else -> ProbeResponse(206, url, "video/mp2t", byteArrayOf(1))
            }
        }
        val link = ExtractorLink(
            "source", "name", "https://cdn.example/master.m3u8", "", 1080,
            type = ExtractorLinkType.M3U8,
        )
        val outcome = LinkResolutionPipeline(probe, FixedClock(1000)).resolve(
            LinkResolutionInput(listOf(link), emptyList(), "Title", lineageId = "lineage")
        )
        assertTrue(outcome is LinkResolutionOutcome.Success)
        assertEquals(3, probe.requests.size)
    }

    @Test
    fun `cross-origin redirect strips sensitive headers but preserves referer`() = runBlocking {
        val probe = RecordingProbe {
            ProbeResponse(206, "https://other.example/video.mp4", "video/mp4", byteArrayOf(1))
        }
        val link = ExtractorLink(
            source = "source",
            name = "name",
            url = "https://origin.example/video.mp4",
            referer = "https://origin.example/page",
            quality = 720,
            headers = mapOf(
                "Authorization" to "Bearer secret",
                "Cookie" to "session=secret",
                "User-Agent" to "webview",
                "Accept" to "video/*",
                "X-Request" to "private",
            ),
            type = ExtractorLinkType.VIDEO,
        )
        val result = LinkResolutionPipeline(probe, FixedClock(1_000L)).resolve(
            LinkResolutionInput(listOf(link), emptyList(), "Title", lineageId = "lineage")
        ) as LinkResolutionOutcome.Success
        val resolved = result.request.links.single()
        assertEquals("https://other.example/video.mp4", resolved.url)
        assertEquals(mapOf("Accept" to "video/*"), resolved.headers)
        assertEquals("https://origin.example/page", resolved.referer)
    }

    @Test
    fun `invalid nested audio URL is dropped and invalid subtitle is omitted`() = runBlocking {
        val probe = RecordingProbe {
            ProbeResponse(206, it, "video/mp4", byteArrayOf(1))
        }
        val invalidAudio = newAudioFile("https://user:pass@cdn.example/audio.m4a")
        val link = ExtractorLink(
            source = "source",
            name = "name",
            url = "https://cdn.example/video.mp4",
            referer = "",
            quality = 720,
            type = ExtractorLinkType.VIDEO,
            audioTracks = listOf(invalidAudio),
        )
        val invalidSubtitle = newSubtitleFile("English", "https://user:pass@cdn.example/sub.vtt")
        val invalidAudioResult = LinkResolutionPipeline(probe, FixedClock(1_000L)).resolve(
            LinkResolutionInput(listOf(link), emptyList(), "Title", lineageId = "lineage")
        ) as LinkResolutionOutcome.Success
        assertTrue(invalidAudioResult.request.links.single().audioTracks.isEmpty())

        val validAudioLink = ExtractorLink(
            source = "source",
            name = "name",
            url = "https://cdn.example/video.mp4",
            referer = "",
            quality = 720,
            type = ExtractorLinkType.VIDEO,
            audioTracks = listOf(newAudioFile("https://cdn.example/audio.m4a")),
        )
        val subtitleResult = LinkResolutionPipeline(probe, FixedClock(1_000L)).resolve(
            LinkResolutionInput(listOf(validAudioLink), listOf(invalidSubtitle), "Title", lineageId = "lineage")
        ) as LinkResolutionOutcome.Success
        assertTrue(subtitleResult.request.subtitles.isEmpty())
    }

    private class FixedClock(private val value: Long) : CompanionClock {
        override fun nowMs(): Long = value
    }

    private data class Request(val url: String, val headers: Map<String, String>, val range: String?)

    private class RecordingProbe(
        private val response: (String) -> ProbeResponse,
    ) : CandidateProbe {
        val requests = mutableListOf<Request>()

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            range: String?,
        ): ProbeResponse {
            requests += Request(url, headers, range)
            return response(url)
        }
    }
}
