package com.lagradost.cloudstream3.companion.ui

import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import com.lagradost.cloudstream3.ui.player.CSPlayerEvent
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.PlayerEventSource
import java.lang.ref.WeakReference

/** Main-thread command seam used by the transport's PLAYER_CMD handler. */
object CompanionPlayerController {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var player: WeakReference<GeneratorPlayer>? = null

    interface Reporter {
        fun register(owner: GeneratorPlayer)
        fun unregister(owner: GeneratorPlayer)
        fun onPlaybackState(
            owner: GeneratorPlayer,
            positionMs: Long,
            durationMs: Long,
            playing: Boolean,
        )
    }

    private var reporter: Reporter? = null

    fun installReporter(reporter: Reporter?) {
        this.reporter = reporter
    }

    @MainThread
    fun register(owner: GeneratorPlayer) {
        player = WeakReference(owner)
        reporter?.register(owner)
    }

    @MainThread
    fun unregister(owner: GeneratorPlayer) {
        reporter?.unregister(owner)
        if (player?.get() === owner) player = null
    }

    fun isRegistered(owner: GeneratorPlayer): Boolean = player?.get() === owner

    fun reportPlaybackState(
        owner: GeneratorPlayer,
        positionMs: Long,
        durationMs: Long,
        playing: Boolean,
    ) {
        reporter?.onPlaybackState(owner, positionMs, durationMs, playing)
    }

    fun dispatch(action: String, positionMs: Long? = null) {
        mainHandler.post {
            val owner = player?.get() ?: return@post
            when (action) {
                "PLAY" -> owner.player.handleEvent(CSPlayerEvent.Play, PlayerEventSource.Sync)
                "PAUSE" -> owner.player.handleEvent(CSPlayerEvent.Pause, PlayerEventSource.Sync)
                "TOGGLE" -> owner.player.handleEvent(
                    CSPlayerEvent.PlayPauseToggle,
                    PlayerEventSource.Sync,
                )
                "SEEK_TO" -> owner.player.seekTime(positionMs ?: return@post, PlayerEventSource.Sync)
                "SEEK_REL" -> owner.player.seekTime(
                    (owner.player.getPosition() ?: 0L) + (positionMs ?: 0L),
                    PlayerEventSource.Sync,
                )
                "NEXT" -> owner.nextEpisode()
                "PREV" -> owner.prevEpisode()
                "STOP" -> owner.releasePlayer()
            }
        }
    }
}
