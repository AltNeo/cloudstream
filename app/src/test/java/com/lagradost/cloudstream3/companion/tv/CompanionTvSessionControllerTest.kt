package com.lagradost.cloudstream3.companion.tv

import com.lagradost.cloudstream3.companion.protocol.Event
import com.lagradost.cloudstream3.companion.protocol.ErrorCode
import com.lagradost.cloudstream3.companion.protocol.LinkType
import com.lagradost.cloudstream3.companion.protocol.PlayRequest
import com.lagradost.cloudstream3.companion.protocol.PlayerAction
import com.lagradost.cloudstream3.companion.protocol.PlayerCommand
import com.lagradost.cloudstream3.companion.protocol.ResolvedLink
import com.lagradost.cloudstream3.companion.protocol.ResultPayload
import com.lagradost.cloudstream3.companion.protocol.EventKind
import com.lagradost.cloudstream3.companion.protocol.PlaybackStateKind
import com.lagradost.cloudstream3.companion.protocol.PlaylistPart
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionTvSessionControllerTest {
    @Test
    fun `play after local stop is stale and cannot restart playback`() = runBlocking {
        val launcher = RecordingLauncher()
        val sink = RecordingSink()
        val controller = CompanionTvSessionController(launcher = launcher, sink = sink)
        val first = request(lineageId = "lineage", attempt = 0)

        assertEquals(PlayAcceptance.Started, controller.handlePlay("phone", "first", first))
        controller.onLocalPlaybackChanged()
        assertEquals(
            PlayAcceptance.Rejected(ErrorCode.STALE_REQUEST),
            controller.handlePlay("phone", "retry", first.copy(attempt = 1)),
        )
        assertEquals(1, launcher.starts.size)
        assertEquals(ErrorCode.STALE_REQUEST, sink.results["retry"]?.error)
        assertTrue(sink.events.any { it.playbackState?.state == PlaybackStateKind.IDLE })
    }

    @Test
    fun `only strictly newer attempt wins and reordered frames are rejected`() = runBlocking {
        val launcher = RecordingLauncher()
        val sink = RecordingSink()
        val controller = CompanionTvSessionController(launcher = launcher, sink = sink)

        assertEquals(
            PlayAcceptance.Started,
            controller.handlePlay("phone", "attempt-0", request("lineage", 0)),
        )
        assertEquals(
            PlayAcceptance.Started,
            controller.handlePlay("phone", "attempt-2", request("lineage", 2)),
        )
        assertEquals(
            PlayAcceptance.Rejected(ErrorCode.STALE_REQUEST),
            controller.handlePlay("phone", "attempt-1", request("lineage", 1)),
        )
        assertEquals(listOf(0, 2), launcher.starts.map { it.attempt })
        assertEquals(ErrorCode.STALE_REQUEST, sink.results["attempt-1"]?.error)
    }

    @Test
    fun `failed candidate emits link failed and keeps session for recovery`() = runBlocking {
        val launcher = RecordingLauncher(
            result = PlaybackStartResult.Failed(
                PlaybackStartFailure(StartupFailureStage.SEGMENT, httpStatus = 403),
            ),
        )
        val sink = RecordingSink()
        val controller = CompanionTvSessionController(launcher = launcher, sink = sink)

        assertEquals(
            PlayAcceptance.WaitingForRecovery,
            controller.handlePlay("phone", "play", request("lineage", 0)),
        )
        assertEquals("lineage", sink.events.single().linkFailed?.lineageId)
        assertEquals(403, sink.events.single().linkFailed?.httpStatus)
        assertEquals("lineage", controller.snapshot()?.lineageId)
    }

    @Test
    fun `navigation commands and end-of-playback request the phone`() = runBlocking {
        val launcher = RecordingLauncher()
        val sink = RecordingSink()
        val controller = CompanionTvSessionController(launcher = launcher, sink = sink)
        controller.onPhoneConnected("phone")
        controller.handlePlay("phone", "play", request("lineage", 0))

        assertEquals(
            ResultPayload(ok = true),
            controller.handlePlayerCommand(
                "phone",
                "next",
                PlayerCommand(PlayerAction.NEXT),
            ),
        )
        assertTrue(
            sink.events.any {
                it.kind == EventKind.NAV_REQUESTED &&
                    it.navRequested?.direction?.name == "NEXT"
            },
        )
        assertTrue(controller.onPlaybackEnded())
        assertEquals(2, sink.events.count { it.kind == EventKind.NAV_REQUESTED })
    }

    @Test
    fun `disconnect during recovery clears session and rejects late recovery`() = runBlocking {
        val launcher = RecordingLauncher(
            result = PlaybackStartResult.Failed(PlaybackStartFailure(StartupFailureStage.MANIFEST)),
        )
        val sink = RecordingSink()
        val controller = CompanionTvSessionController(launcher = launcher, sink = sink)
        controller.onPhoneConnected("phone")
        controller.handlePlay("phone", "play", request("lineage", 0))
        controller.onPhoneDisconnected("phone")

        assertNull(controller.snapshot())
        assertTrue(sink.events.any { it.playbackState?.state == PlaybackStateKind.IDLE })
        assertEquals(
            PlayAcceptance.Rejected(ErrorCode.STALE_REQUEST),
            controller.handlePlay("phone", "late", request("lineage", 1)),
        )
        assertEquals(ErrorCode.STALE_REQUEST, sink.results["late"]?.error)
    }

    @Test
    fun `unpair ends an active session even outside recovery`() = runBlocking {
        val launcher = RecordingLauncher()
        val sink = RecordingSink()
        val controller = CompanionTvSessionController(launcher = launcher, sink = sink)
        controller.onPhoneConnected("phone")
        controller.handlePlay("phone", "play", request("lineage", 0))

        controller.onPhoneUnpaired("phone")

        assertNull(controller.snapshot())
        assertTrue(sink.events.any { it.playbackState?.state == PlaybackStateKind.IDLE })
        assertEquals(
            PlayAcceptance.Rejected(ErrorCode.STALE_REQUEST),
            controller.handlePlay("phone", "late", request("lineage", 1)),
        )
    }

    @Test
    fun `expired candidates are skipped before launcher is called`() = runBlocking {
        val launcher = RecordingLauncher()
        val sink = RecordingSink()
        val controller = CompanionTvSessionController(
            launcher = launcher,
            sink = sink,
            clock = CompanionClock { 1000L },
        )
        val expired = link("https://expired.example/video.mp4").copy(expiresAtMs = 999L)
        val request = request("lineage", 0).copy(links = listOf(expired))

        assertEquals(
            PlayAcceptance.WaitingForRecovery,
            controller.handlePlay("phone", "play", request),
        )
        assertTrue(launcher.starts.isEmpty())
        assertEquals(0, sink.events.single().linkFailed?.linkIndex)
    }

    @Test
    fun `playlist candidate is accepted with ordered parts when url is blank`() = runBlocking {
        val launcher = RecordingLauncher()
        val controller = CompanionTvSessionController(launcher = launcher)
        val playlist = link("").copy(
            playlist = listOf(
                PlaylistPart("https://cdn.example/one.ts", 100),
                PlaylistPart("https://cdn.example/two.ts", 200),
            ),
        )

        assertEquals(
            PlayAcceptance.Started,
            controller.handlePlay(
                "phone",
                "playlist",
                request("lineage", 0).copy(links = listOf(playlist)),
            ),
        )
    }

    @Test
    fun `local stop can invalidate a startup without waiting for launcher`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val launcher = object : TvPlaybackLauncher {
            override suspend fun startCandidate(
                request: PlayRequest,
                candidate: ResolvedLink,
                startPositionMs: Long?,
                deadlineMs: Long,
            ): PlaybackStartResult {
                entered.complete(Unit)
                release.await()
                return PlaybackStartResult.Started
            }

            override fun stop() = Unit
        }
        val controller = CompanionTvSessionController(launcher = launcher)
        val playJob = launch {
            controller.handlePlay("phone", "play", request("lineage", 0))
        }

        entered.await()
        withTimeout(1_000L) { controller.onLocalPlaybackChanged() }
        release.complete(Unit)
        playJob.join()
        assertNull(controller.snapshot())
    }

    private fun request(lineageId: String, attempt: Int): PlayRequest = PlayRequest(
        lineageId = lineageId,
        attempt = attempt,
        links = listOf(link("https://cdn.example/video.mp4")),
        subtitles = emptyList(),
        title = "Title",
    )

    private fun link(url: String): ResolvedLink = ResolvedLink(
        url = url,
        type = LinkType.VIDEO,
        quality = 1080,
        sourceName = "Source",
        issuedAtMs = 0,
    )
}

private class RecordingLauncher(
    private val result: PlaybackStartResult = PlaybackStartResult.Started,
) : TvPlaybackLauncher {
    val starts = mutableListOf<PlayRequest>()
    var stopCount = 0

    override suspend fun startCandidate(
        request: PlayRequest,
        candidate: ResolvedLink,
        startPositionMs: Long?,
        deadlineMs: Long,
    ): PlaybackStartResult {
        starts += request
        return result
    }

    override fun stop() {
        stopCount += 1
    }
}

private class RecordingSink : CompanionTvSessionSink {
    val results = mutableMapOf<String, ResultPayload>()
    val events = mutableListOf<Event>()
    val statuses = mutableListOf<TvStatusMessage>()

    override fun sendResult(requestId: String, result: ResultPayload) {
        results[requestId] = result
    }

    override fun sendEvent(event: Event) {
        events += event
    }

    override fun showStatus(message: TvStatusMessage) {
        statuses += message
    }
}
