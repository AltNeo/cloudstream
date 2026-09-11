package com.lagradost.cloudstream3.companion.tv

import com.lagradost.cloudstream3.companion.protocol.Event
import com.lagradost.cloudstream3.companion.protocol.EventKind
import com.lagradost.cloudstream3.companion.protocol.PlaybackState
import com.lagradost.cloudstream3.companion.protocol.PlaybackStateKind

data class NowPlayingSnapshot(
    val lineageId: String,
    val metadata: CompanionPlaybackMetadata,
    val positionMs: Long,
    val durationMs: Long,
    val state: PlaybackStateKind,
)

/** Emits immediate transitions and throttles ordinary PLAYING progress to [cadenceMs]. */
class CompanionNowPlayingHub(
    private val clock: CompanionClock = CompanionClock(System::currentTimeMillis),
    private val eventSink: (Event) -> Unit,
    private val cadenceMs: Long = 3_000L,
) : CompanionNowPlayingReporter {
    init {
        require(cadenceMs > 0L)
    }

    private var snapshot: NowPlayingSnapshot? = null
    private var lastPlayingEmissionMs: Long? = null

    @Synchronized
    override fun register(lineageId: String, metadata: CompanionPlaybackMetadata) {
        snapshot = NowPlayingSnapshot(
            lineageId = lineageId,
            metadata = metadata,
            positionMs = 0L,
            durationMs = metadata.durationMs ?: 0L,
            state = PlaybackStateKind.PLAYING,
        )
        // Registration emits the initial PLAYING transition, so ordinary progress is
        // throttled relative to that emission as well.
        lastPlayingEmissionMs = clock.nowMs()
        emit(snapshot!!)
    }

    @Synchronized
    override fun unregister(lineageId: String) {
        val current = snapshot ?: return
        if (current.lineageId != lineageId) return
        val idle = current.copy(state = PlaybackStateKind.IDLE)
        snapshot = idle
        emit(idle)
        snapshot = null
        lastPlayingEmissionMs = null
    }

    @Synchronized
    fun onPlaybackState(
        state: PlaybackStateKind,
        positionMs: Long,
        durationMs: Long,
    ): Boolean {
        val current = snapshot ?: return false
        val next = current.copy(
            positionMs = positionMs.coerceAtLeast(0L),
            durationMs = durationMs.coerceAtLeast(0L),
            state = state,
        )
        snapshot = next
        val now = clock.nowMs()
        if (state == PlaybackStateKind.PLAYING) {
            val last = lastPlayingEmissionMs
            if (last != null && now - last < cadenceMs) return false
            lastPlayingEmissionMs = now
        }
        emit(next)
        if (state == PlaybackStateKind.IDLE) {
            snapshot = null
            lastPlayingEmissionMs = null
        }
        return true
    }

    @Synchronized
    fun snapshot(): NowPlayingSnapshot? = snapshot

    private fun emit(value: NowPlayingSnapshot) {
        eventSink(
            Event(
                kind = EventKind.PLAYBACK_STATE,
                playbackState = PlaybackState(
                    lineageId = value.lineageId,
                    title = value.metadata.title,
                    episodeLabel = value.metadata.episodeLabel,
                    posterUrl = value.metadata.posterUrl,
                    positionMs = value.positionMs,
                    durationMs = value.durationMs,
                    state = value.state,
                    mediaId = value.metadata.mediaId,
                ),
            ),
        )
    }
}
