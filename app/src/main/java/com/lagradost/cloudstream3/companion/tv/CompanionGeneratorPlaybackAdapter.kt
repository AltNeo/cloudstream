package com.lagradost.cloudstream3.companion.tv

import com.lagradost.cloudstream3.companion.protocol.PlaybackStateKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Bridges player callbacks to the session state machine and now-playing event cadence. */
class CompanionGeneratorPlaybackAdapter(
    private val scope: CoroutineScope,
    private val controller: CompanionTvSessionController,
    private val nowPlaying: CompanionNowPlayingHub,
    private val mainThread: CompanionTvMainThreadDispatcher =
        ImmediateCompanionTvMainThreadDispatcher,
    private val isCurrent: () -> Boolean = { true },
) : CompanionGeneratorPlaybackListener {
    override fun onPlaybackState(state: PlaybackStateKind, positionMs: Long, durationMs: Long) {
        if (!isCurrent()) return
        scope.launch {
            if (!isCurrent()) return@launch
            mainThread.dispatch {
                if (!isCurrent()) return@dispatch
                nowPlaying.onPlaybackState(state, positionMs, durationMs)
                controller.onPlaybackPosition(positionMs)
            }
        }
    }

    override fun onLinkFailure(linkIndex: Int, failure: PlaybackStartFailure) {
        if (!isCurrent()) return
        scope.launch {
            if (!isCurrent()) return@launch
            mainThread.dispatch {
                if (!isCurrent()) return@dispatch
                controller.onPlaybackFailure(linkIndex, failure)
            }
        }
    }

    override fun onPlaybackEnded() {
        if (!isCurrent()) return
        scope.launch {
            if (!isCurrent()) return@launch
            mainThread.dispatch {
                if (!isCurrent()) return@dispatch
                nowPlaying.onPlaybackState(PlaybackStateKind.ENDED, 0L, 0L)
                controller.onPlaybackEnded()
            }
        }
    }

    override fun onLocalPlaybackChanged() {
        if (!isCurrent()) return
        scope.launch {
            if (!isCurrent()) return@launch
            mainThread.dispatch {
                if (!isCurrent()) return@dispatch
                controller.onLocalPlaybackChanged()
            }
        }
    }
}
