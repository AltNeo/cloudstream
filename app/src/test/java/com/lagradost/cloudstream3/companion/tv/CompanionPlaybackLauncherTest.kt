package com.lagradost.cloudstream3.companion.tv

import com.lagradost.cloudstream3.companion.protocol.LinkType
import com.lagradost.cloudstream3.companion.protocol.PlayRequest
import com.lagradost.cloudstream3.companion.protocol.PlaybackStateKind
import com.lagradost.cloudstream3.companion.protocol.ResolvedLink
import com.lagradost.cloudstream3.companion.protocol.ResolvedSubtitle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionPlaybackLauncherTest {
    @Test
    fun `launcher converts link and subtitles and registers now playing`() = runBlocking {
        val factory = RecordingFactory()
        val reporter = RecordingReporter()
        val launcher = CompanionPlaybackLauncher(factory, reporter, nowMs = { 100L })
        val request = PlayRequest(
            lineageId = "lineage",
            attempt = 0,
            links = listOf(link()),
            subtitles = listOf(
                ResolvedSubtitle(
                    url = "https://cdn.example/sub.vtt",
                    lang = "English",
                    mimeType = "text/vtt",
                    headers = mapOf("Cookie" to "cookie"),
                ),
            ),
            title = "Title",
            mediaId = 12,
            durationMs = 1000,
        )

        assertEquals(
            PlaybackStartResult.Started,
            launcher.startCandidate(request, request.links.single(), 77L, deadlineMs = 200L),
        )
        assertEquals("https://cdn.example/video.mp4", factory.link?.url)
        assertEquals("https://cdn.example/sub.vtt", factory.subtitles.single().url)
        assertEquals(mapOf("Cookie" to "cookie"), factory.subtitles.single().headers)
        assertEquals(77L, factory.player?.positionMs)
        assertEquals(listOf("lineage"), reporter.registered)

        launcher.stop()
        assertTrue(factory.player?.released == true)
        assertEquals(listOf("lineage"), reporter.unregistered)
    }

    @Test
    fun `launcher rejects candidate past deadline`() = runBlocking {
        val factory = RecordingFactory()
        val launcher = CompanionPlaybackLauncher(factory, RecordingReporter(), nowMs = { 300L })
        val request = PlayRequest("lineage", 0, listOf(link()), emptyList(), "Title")

        val result = launcher.startCandidate(
            request,
            request.links.single(),
            null,
            deadlineMs = 200L,
        )
        assertTrue(result is PlaybackStartResult.Failed)
        assertTrue(factory.link == null)
    }

    @Test
    fun `replacement unregisters old reporter before releasing old player`() = runBlocking {
        val factory = RecordingFactory()
        val reporter = RecordingReporter()
        val launcher = CompanionPlaybackLauncher(factory, reporter, nowMs = { 100L })
        val first = PlayRequest("first", 0, listOf(link()), emptyList(), "First")
        val second = PlayRequest("second", 0, listOf(link()), emptyList(), "Second")

        launcher.startCandidate(first, first.links.single(), null, deadlineMs = 200L)
        launcher.startCandidate(second, second.links.single(), null, deadlineMs = 200L)

        assertTrue(factory.players.first().released)
        assertEquals(listOf("first"), reporter.unregistered)
    }

    @Test
    fun `callbacks from a replaced player are ignored`() = runBlocking {
        val factory = RecordingFactory()
        val listener = CountingPlaybackListener()
        val launcher = CompanionPlaybackLauncher(
            factory = factory,
            reporter = RecordingReporter(),
            nowMs = { 100L },
            playbackListener = listener,
        )
        val first = PlayRequest("first", 0, listOf(link()), emptyList(), "First")
        val second = PlayRequest("second", 0, listOf(link()), emptyList(), "Second")

        launcher.startCandidate(first, first.links.single(), null, deadlineMs = 200L)
        launcher.startCandidate(second, second.links.single(), null, deadlineMs = 200L)
        factory.players[0].listener?.onPlaybackEnded()
        factory.players[1].listener?.onPlaybackEnded()

        assertEquals(1, listener.ended)
    }

    private fun link() = ResolvedLink(
        url = "https://cdn.example/video.mp4",
        type = LinkType.VIDEO,
        quality = 1080,
        sourceName = "Source",
        referer = "https://example.test/",
        headers = mapOf("User-Agent" to "test"),
        issuedAtMs = 1,
    )
}

private class RecordingFactory : CompanionGeneratorFactory {
    var link: com.lagradost.cloudstream3.utils.ExtractorLink? = null
    var subtitles = emptyList<com.lagradost.cloudstream3.SubtitleFile>()
    var player: RecordingPlayer? = null
    val players = mutableListOf<RecordingPlayer>()

    override fun create(
        link: com.lagradost.cloudstream3.utils.ExtractorLink,
        subtitles: List<com.lagradost.cloudstream3.SubtitleFile>,
        metadata: CompanionPlaybackMetadata,
    ): CompanionGeneratorPlayer {
        this.link = link
        this.subtitles = subtitles
        return RecordingPlayer().also {
            player = it
            players += it
        }
    }
}

private class RecordingPlayer : CompanionGeneratorPlayer, CompanionGeneratorPlayerEventSource {
    var positionMs: Long? = null
    var released = false
    var listener: CompanionGeneratorPlaybackListener? = null

    override fun start(startPositionMs: Long?) {
        positionMs = startPositionMs
    }

    override fun release() {
        released = true
    }

    override fun setPlaybackListener(listener: CompanionGeneratorPlaybackListener) {
        this.listener = listener
    }
}

private class RecordingReporter : CompanionNowPlayingReporter {
    val registered = mutableListOf<String>()
    val unregistered = mutableListOf<String>()

    override fun register(lineageId: String, metadata: CompanionPlaybackMetadata) {
        registered += lineageId
    }

    override fun unregister(lineageId: String) {
        unregistered += lineageId
    }
}

private class CountingPlaybackListener : CompanionGeneratorPlaybackListener {
    var ended = 0

    override fun onPlaybackState(
        state: PlaybackStateKind,
        positionMs: Long,
        durationMs: Long,
    ) = Unit

    override fun onLinkFailure(linkIndex: Int, failure: PlaybackStartFailure) = Unit

    override fun onPlaybackEnded() {
        ended += 1
    }

    override fun onLocalPlaybackChanged() = Unit
}
