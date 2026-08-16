package com.lagradost.cloudstream3.remote

import com.lagradost.cloudstream3.ui.player.AudioTrack
import com.lagradost.cloudstream3.ui.player.CurrentTracks
import com.lagradost.cloudstream3.ui.player.TextTrack
import com.lagradost.cloudstream3.ui.player.VideoTrack
import com.lagradost.cloudstream3.remote.server.NowPlayingHub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionUiTest {

    // ------------------------------------------------------------------
    // formatPlaybackTime
    // ------------------------------------------------------------------

    @Test
    fun `formatPlaybackTime sub-minute is mm ss`() {
        assertEquals("00:42", formatPlaybackTime(42_000))
    }

    @Test
    fun `formatPlaybackTime over an hour is h mm ss`() {
        assertEquals("1:02:03", formatPlaybackTime(3_723_000))
    }

    @Test
    fun `formatPlaybackTime clamps negatives to zero`() {
        assertEquals("00:00", formatPlaybackTime(-5))
    }

    @Test
    fun `formatPlaybackTime zero is 00 00`() {
        assertEquals("00:00", formatPlaybackTime(0))
    }

    // ------------------------------------------------------------------
    // PluginSyncResult.Status.tvSyncBadge
    // ------------------------------------------------------------------

    @Test
    fun `successful sync statuses show a checkmark`() {
        assertEquals("✓", PluginSyncResult.Status.OK_INSTALLED.tvSyncBadge())
        assertEquals("✓", PluginSyncResult.Status.OK_ALREADY.tvSyncBadge())
        assertEquals("✓", PluginSyncResult.Status.UPDATED.tvSyncBadge())
    }

    @Test
    fun `pending sync statuses show a refresh symbol`() {
        assertEquals("↻", PluginSyncResult.Status.NEWER_KEPT.tvSyncBadge())
        assertEquals("↻", PluginSyncResult.Status.DOWNLOAD_ONLY_SAFE_MODE.tvSyncBadge())
    }

    @Test
    fun `failed and removed statuses show a cross`() {
        assertEquals("✕", PluginSyncResult.Status.FAILED.tvSyncBadge())
        assertEquals("✕", PluginSyncResult.Status.REMOVED.tvSyncBadge())
    }

    // ------------------------------------------------------------------
    // NowPlayingPayload.State.isActive
    // ------------------------------------------------------------------

    @Test
    fun `unknown now playing state counts as inactive`() {
        assertTrue(NowPlayingPayload.State.PLAYING.isActive)
        assertTrue(NowPlayingPayload.State.PAUSED.isActive)
        assertTrue(NowPlayingPayload.State.BUFFERING.isActive)
        assertFalse(NowPlayingPayload.State.IDLE.isActive)
        assertFalse(NowPlayingPayload.State.ENDED.isActive)
        assertFalse(NowPlayingPayload.State.UNKNOWN.isActive)
    }

    // ------------------------------------------------------------------
    // InputContext.Context.UNKNOWN policy (finding 4)
    // ------------------------------------------------------------------

    @Test
    fun `unknown input context is never stored as the active context`() {
        // A lenient-decoded UNKNOWN surface cannot be interpreted, so it must not become
        // the active input context: no input UI is shown and the send/echo guard must not
        // treat its currentText as the TV's echoed value.
        assertNull(
            effectiveInputContext(
                InputContextPayload(
                    context = InputContextPayload.Context.UNKNOWN,
                    currentText = "untrusted",
                )
            )
        )
        // Known surfaces pass through unchanged.
        val search = InputContextPayload(
            context = InputContextPayload.Context.SEARCH_FIELD,
            currentText = "hello",
        )
        assertEquals(search, effectiveInputContext(search))
        val idle = InputContextPayload(context = InputContextPayload.Context.IDLE)
        assertEquals(idle, effectiveInputContext(idle))
        // No event at all stays null.
        assertNull(effectiveInputContext(null))
    }

    // ------------------------------------------------------------------
    // interpolatedPositionMs (checkpoint 2: live phone-side position)
    // ------------------------------------------------------------------

    @Test
    fun `playing position advances monotonically with elapsed time and speed`() {
        val payload = NowPlayingPayload(
            positionMs = 60_000,
            durationMs = 600_000,
            state = NowPlayingPayload.State.PLAYING,
            speed = 1f,
        )
        // 10 s after the sample the position moved forward exactly 10 s.
        assertEquals(70_000, interpolatedPositionMs(payload, sampledAtMs = 1_000, atMs = 11_000))
        // A later wall-clock time projects further forward (monotonic in time).
        assertEquals(75_000, interpolatedPositionMs(payload, sampledAtMs = 1_000, atMs = 16_000))
    }

    @Test
    fun `playing position scales with reported speed`() {
        val payload = NowPlayingPayload(
            positionMs = 100_000,
            durationMs = 1_000_000,
            state = NowPlayingPayload.State.PLAYING,
            speed = 2f,
        )
        // At 2x, 5 s of wall clock advances the position 10 s.
        assertEquals(110_000, interpolatedPositionMs(payload, sampledAtMs = 0, atMs = 5_000))
    }

    @Test
    fun `playing position never exceeds the duration`() {
        val payload = NowPlayingPayload(
            positionMs = 590_000,
            durationMs = 600_000,
            state = NowPlayingPayload.State.PLAYING,
        )
        // 30 s of wall clock would project past the end; it clamps to the duration.
        assertEquals(600_000, interpolatedPositionMs(payload, sampledAtMs = 0, atMs = 30_000))
    }

    @Test
    fun `playing position never drops below the sampled position`() {
        val payload = NowPlayingPayload(
            positionMs = 100_000,
            durationMs = 1_000_000,
            state = NowPlayingPayload.State.PLAYING,
        )
        // A clock that runs backwards must not move the position backwards (monotonic floor).
        assertEquals(100_000, interpolatedPositionMs(payload, sampledAtMs = 10_000, atMs = 1_000))
        // At the sample instant it is exactly the sampled position.
        assertEquals(100_000, interpolatedPositionMs(payload, sampledAtMs = 5_000, atMs = 5_000))
    }

    @Test
    fun `non playing states return the sampled position unchanged`() {
        NowPlayingPayload.State.entries
            .filter { it != NowPlayingPayload.State.PLAYING }
            .forEach { state ->
                val payload = NowPlayingPayload(
                    positionMs = 42_000,
                    durationMs = 600_000,
                    state = state,
                    speed = 1f,
                )
                // No interpolation for PAUSED / BUFFERING / ENDED / IDLE / UNKNOWN.
                assertEquals(42_000, interpolatedPositionMs(payload, sampledAtMs = 0, atMs = 90_000))
            }
    }

    @Test
    fun `playing without a known duration stays at the sampled position`() {
        val payload = NowPlayingPayload(
            positionMs = 5_000,
            durationMs = 0,
            state = NowPlayingPayload.State.PLAYING,
        )
        assertEquals(5_000, interpolatedPositionMs(payload, sampledAtMs = 0, atMs = 60_000))
    }

    @Test
    fun `invalid speed falls back to normal speed`() {
        val payload = NowPlayingPayload(
            positionMs = 10_000,
            durationMs = 1_000_000,
            state = NowPlayingPayload.State.PLAYING,
            speed = -1f, // decode fallback / corrupted value
        )
        // Negative speed must not rewind; it is treated as 1x.
        assertEquals(20_000, interpolatedPositionMs(payload, sampledAtMs = 0, atMs = 10_000))
    }

    // ------------------------------------------------------------------
    // RemoteState (checkpoint 1: unified observable session state)
    // ------------------------------------------------------------------

    @Test
    fun `remote state defaults are safe and coherent`() {
        val state = RemoteState()
        assertNull(state.activeTv)
        assertFalse(state.tvOnline)
        assertNull(state.nowPlaying)
        assertNull(state.inputContext)
        assertTrue(state.tvCapabilities.isEmpty())
        assertNull(state.lastSyncTime)
        assertEquals(SyncStatus.IDLE, state.lastSyncStatus)
        // Offline and capability-less means no whole-string input.
        assertFalse(state.canSendInputText)
    }

    @Test
    fun `canSendInputText requires online tv with input text capability`() {
        val caps = setOf(DeviceInfo.CAP_INPUT_TEXT, DeviceInfo.CAP_INPUT_CONTEXT)
        assertTrue(RemoteState(tvOnline = true, tvCapabilities = caps).canSendInputText)
        // Capability present but TV unreachable.
        assertFalse(RemoteState(tvOnline = false, tvCapabilities = caps).canSendInputText)
        // Online but a legacy TV without whole-string input.
        assertFalse(
            RemoteState(tvOnline = true, tvCapabilities = setOf(DeviceInfo.CAP_INPUT_CONTEXT))
                .canSendInputText
        )
    }

    @Test
    fun `remote state copy keeps unrelated fields`() {
        val state = RemoteState(
            tvOnline = true,
            nowPlaying = NowPlayingPayload(title = "T", state = NowPlayingPayload.State.PLAYING),
        )
        val updated = state.copy(inputContext = null)
        assertEquals(state.tvOnline, updated.tvOnline)
        assertEquals(state.nowPlaying, updated.nowPlaying)
        assertNull(updated.inputContext)
    }

    @Test
    fun `shared direct media urls are classified without accepting provider pages`() {
        assertEquals(
            "video/mp4",
            classifySharedUrl("https://cdn.example/movie.mp4?token=x")?.mimeType,
        )
        assertEquals(
            "application/x-mpegURL",
            classifySharedUrl("https://cdn.example/live/index.M3U8")?.mimeType,
        )
        assertEquals(
            "application/dash+xml",
            classifySharedUrl("https://cdn.example/manifest.mpd#start")?.mimeType,
        )
        assertNull(classifySharedUrl("https://provider.example/watch/123"))
        assertNull(classifySharedUrl("file:///sdcard/movie.mp4"))
        assertNull(classifySharedUrl("magnet:?xt=urn:btih:test"))
    }

    @Test
    fun `shared text extracts one explicit http media url`() {
        val candidate = sharedUrlCandidate("Watch: https://cdn.example/movie.webm now")
        assertEquals("https://cdn.example/movie.webm", candidate)
        assertEquals("video/mp4", classifySharedUrl(candidate)?.mimeType)
        assertNull(sharedUrlCandidate("no clipboard or link here"))
    }

    @Test
    fun `renderer tracks map video and audio metadata but never expose text ids`() {
        val currentTracks = CurrentTracks(
                currentVideoTrack = VideoTrack("v1", "720p", null, 1280, 720, "video/avc"),
                currentAudioTrack = AudioTrack("a1", "English", "en", "audio/aac", 2, 3),
                currentTextTracks = listOf(TextTrack("secret", "English", "en", "text/vtt")),
                allVideoTracks = listOf(
                    VideoTrack("v1", "720p", null, 1280, 720, "video/avc")
                ),
                allAudioTracks = listOf(
                    AudioTrack("a1", "English", "en", "audio/aac", 2, 3)
                ),
                allTextTracks = listOf(TextTrack("secret", "English", "en", "text/vtt")),
            )
        val payload = tracksPayloadFrom(currentTracks)
        assertEquals(1280, payload.videoTracks.single().width)
        assertEquals(720, payload.videoTracks.single().height)
        assertEquals(3, payload.audioTracks.single().formatIndex)
        assertTrue(payload.subtitlesEnabled)
        assertTrue(payload.hasTextTracks)
        assertFalse(payload.toString().contains("secret"))
        assertTrue(
            NowPlayingHub.isSelectionAvailable(
                SelectTrackPayload(SelectTrackPayload.TrackType.VIDEO, "v1"),
                currentTracks,
            )
        )
        assertFalse(
            NowPlayingHub.isSelectionAvailable(
                SelectTrackPayload(SelectTrackPayload.TrackType.VIDEO, "stale"),
                currentTracks,
            )
        )
        assertFalse(
            NowPlayingHub.isSelectionAvailable(
                SelectTrackPayload(SelectTrackPayload.TrackType.AUDIO, "stale"),
                currentTracks,
            )
        )
    }
}
