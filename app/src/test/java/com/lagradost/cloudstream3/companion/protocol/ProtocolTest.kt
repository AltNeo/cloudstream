package com.lagradost.cloudstream3.companion.protocol

import com.lagradost.cloudstream3.newAudioFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkPlayList
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.PlayListItem
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    @Test
    fun `envelope round trips payload fields`() {
        val request = PlayRequest(
            lineageId = "lineage",
            attempt = 2,
            links = listOf(
                ResolvedLink(
                    url = "https://cdn.example/video.m3u8",
                    type = LinkType.M3U8,
                    quality = 1080,
                    sourceName = "Example",
                    referer = "https://example.test/",
                    headers = mapOf("User-Agent" to "test", "x-token" to "abc"),
                    audioTracks = listOf(
                        ResolvedAudioTrack("https://cdn.example/audio.m4a", mapOf("Cookie" to "c")),
                    ),
                    playlist = listOf(
                        PlaylistPart("https://cdn.example/one.ts", 123),
                        PlaylistPart("https://cdn.example/two.ts", 456),
                    ),
                    issuedAtMs = 100,
                    expiresAtMs = 200,
                ),
            ),
            subtitles = listOf(
                ResolvedSubtitle(
                    url = "https://cdn.example/en.vtt",
                    lang = "English",
                    mimeType = "text/vtt",
                    headers = mapOf("Authorization" to "Bearer test"),
                ),
            ),
            title = "Episode",
            episodeLabel = "S1E2",
            mediaId = 42,
            startPositionMs = 99,
            durationMs = 1000,
        )
        val envelope = Envelope(
            id = "request-id",
            type = MessageType.PLAY,
            payload = ProtocolJson.encode(PlayRequest.serializer(), request),
        )

        val decodedEnvelope = ProtocolJson.decodeEnvelope(ProtocolJson.encodeEnvelope(envelope))
        val decoded = ProtocolJson.decode(PlayRequest.serializer(), decodedEnvelope.payload!!)
        assertEquals(request, decoded)
    }

    @Test
    fun `unknown envelope fields are ignored`() {
        val json = """
            {"v":3,"id":"id","type":"PING","payload":null,"futureField":{"x":1}}
        """.trimIndent()
        val decoded = ProtocolJson.decodeEnvelope(json.encodeToByteArray())
        assertEquals(MessageType.PING, decoded.type)
        assertEquals("id", decoded.id)
    }

    @Test
    fun `track selection payloads and catalog preserve optional fields`() {
        val request = SelectSubtitleRequest(index = null, lineageId = "lineage")
        val decodedRequest = ProtocolJson.decode(
            SelectSubtitleRequest.serializer(),
            ProtocolJson.encode(SelectSubtitleRequest.serializer(), request),
        )
        assertEquals(request, decodedRequest)

        val catalog = TracksAvailable(
            lineageId = "lineage",
            sources = listOf(TrackOption(0, "1080p", quality = 1080, sourceName = "cdn")),
            audioTracks = listOf(TrackOption(2, "English", language = "en")),
            subtitles = listOf(TrackOption(4, "Deutsch", language = "de")),
            selectedSourceIndex = 0,
            selectedSubtitleIndex = null,
        )
        val event = Event(EventKind.TRACKS_AVAILABLE, tracksAvailable = catalog)
        val decodedEvent = ProtocolJson.decode(
            Event.serializer(),
            ProtocolJson.encode(Event.serializer(), event),
        )
        assertEquals(event, decodedEvent)
    }

    @Test
    fun `frame codec uses big endian length and preserves bytes`() {
        val payload = byteArrayOf(0, 1, 2, -1)
        val output = ByteArrayOutputStream()
        FrameCodec.writeFrame(output, payload)
        assertArrayEquals(byteArrayOf(0, 0, 0, 4, 0, 1, 2, -1), output.toByteArray())
        assertArrayEquals(payload, FrameCodec.readFrame(ByteArrayInputStream(output.toByteArray())))
    }

    @Test
    fun `playlist and audio tracks convert without losing order or durations`() {
        val original = ExtractorLinkPlayList(
            source = "source",
            name = "name",
            playlist = listOf(PlayListItem("https://one", 11), PlayListItem("https://two", 22)),
            referer = "https://referer",
            quality = 720,
            headers = mapOf("Cookie" to "cookie"),
            type = ExtractorLinkType.M3U8,
            audioTracks = listOf(
                kotlinx.coroutines.runBlocking {
                    newAudioFile("https://audio") { headers = mapOf("x-audio" to "yes") }
                },
            ),
        )
        val restored = original.toResolvedLink(issuedAtMs = 1).toExtractorLink()
        assertTrue(restored is ExtractorLinkPlayList)
        restored as ExtractorLinkPlayList
        assertEquals(listOf(11L, 22L), restored.playlist.map(PlayListItem::durationUs))
        assertEquals(listOf("https://one", "https://two"), restored.playlist.map(PlayListItem::url))
        assertEquals("https://referer", restored.referer)
        assertEquals("https://audio", restored.audioTracks.single().url)
        assertEquals(mapOf("x-audio" to "yes"), restored.audioTracks.single().headers)
    }

    @Test
    fun `direct link conversion preserves request metadata`() {
        val original = ExtractorLink(
            source = "source",
            name = "display name",
            url = "https://cdn.example/video.mp4",
            referer = "https://example.test/",
            quality = 1080,
            headers = mapOf("User-Agent" to "test", "Cookie" to "cookie"),
            type = ExtractorLinkType.VIDEO,
            audioTracks = listOf(
                runBlocking {
                    newAudioFile("https://cdn.example/audio.m4a") {
                        headers = mapOf("Authorization" to "token")
                    }
                },
            ),
        )
        val restored = original.toResolvedLink(issuedAtMs = 123).toExtractorLink()
        assertEquals("https://cdn.example/video.mp4", restored.url)
        assertEquals("https://example.test/", restored.referer)
        assertEquals(1080, restored.quality)
        assertEquals(original.headers, restored.headers)
        assertEquals(ExtractorLinkType.VIDEO, restored.type)
        assertEquals("https://cdn.example/audio.m4a", restored.audioTracks.single().url)
        assertEquals(mapOf("Authorization" to "token"), restored.audioTracks.single().headers)
    }

    @Test
    fun `subtitle conversion preserves headers`() {
        val original = runBlocking {
            newSubtitleFile("English", "https://cdn.example/en.vtt") {
                headers = mapOf("Cookie" to "cookie")
            }
        }
        val restored = original.toResolvedSubtitle(mimeType = "text/vtt").toSubtitleFile()
        assertEquals(original.lang, restored.lang)
        assertEquals(original.url, restored.url)
        assertEquals(original.headers, restored.headers)
    }
}
