package com.lagradost.cloudstream3.companion.ui

import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import com.lagradost.cloudstream3.companion.protocol.NavigationDirection
import com.lagradost.cloudstream3.ui.player.CSPlayerEvent
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.PlayerEventSource
import java.lang.ref.WeakReference

/** Launch identity read from the player fragment's companion arguments. */
data class CompanionLaunchIdentity(
    val lineageId: String,
    val launchToken: String,
)

/** Main-thread command seam used by the transport's PLAYER_CMD handler. */
object CompanionPlayerController {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var player: WeakReference<GeneratorPlayer>? = null
    private var expectedRemoteRelease: WeakReference<GeneratorPlayer>? = null

    /** TV-side navigation interception: true when a remote session handled the request. */
    fun interface TvNavigator {
        fun requestNavigation(direction: NavigationDirection): Boolean
    }

    interface Reporter {
        fun register(owner: GeneratorPlayer, launch: CompanionLaunchIdentity?)
        fun unregister(owner: GeneratorPlayer)
        fun onPlaybackState(
            owner: GeneratorPlayer,
            positionMs: Long,
            durationMs: Long,
            playing: Boolean,
        )

        fun onPlaybackError(owner: GeneratorPlayer, exception: Throwable)
    }

    private var reporter: Reporter? = null
    private var tvNavigator: TvNavigator? = null

    fun installReporter(reporter: Reporter?) {
        this.reporter = reporter
    }

    fun installTvNavigator(navigator: TvNavigator?) {
        this.tvNavigator = navigator
    }

    @MainThread
    fun register(owner: GeneratorPlayer, launch: CompanionLaunchIdentity?) {
        player = WeakReference(owner)
        reporter?.register(owner, launch)
    }

    @MainThread
    fun unregister(owner: GeneratorPlayer) {
        val suppress = expectedRemoteRelease?.get() === owner
        if (suppress) expectedRemoteRelease = null else reporter?.unregister(owner)
        if (player?.get() === owner) player = null
    }

    fun isRegistered(owner: GeneratorPlayer): Boolean = player?.get() === owner

    fun hasActivePlayer(): Boolean = player?.get() != null

    fun reportPlaybackState(
        owner: GeneratorPlayer,
        positionMs: Long,
        durationMs: Long,
        playing: Boolean,
    ) {
        reporter?.onPlaybackState(owner, positionMs, durationMs, playing)
    }

    fun reportPlaybackError(owner: GeneratorPlayer, exception: Throwable) {
        reporter?.onPlaybackError(owner, exception)
    }

    /**
     * Called by the player before resolving the next/previous episode locally (manual TV remote
     * navigation and auto-advance on ENDED). Returns true when a companion remote session owns
     * episode navigation and local resolution must not run.
     */
    fun requestRemoteNavigation(direction: NavigationDirection): Boolean =
        tvNavigator?.requestNavigation(direction) ?: false

    fun dispatch(action: String, positionMs: Long? = null): Boolean {
        val owner = player?.get() ?: return false
        mainHandler.post {
            val current = player?.get() ?: return@post
            if (current !== owner) return@post
            when (action) {
                "PLAY" -> current.player.handleEvent(CSPlayerEvent.Play, PlayerEventSource.Sync)
                "PAUSE" -> current.player.handleEvent(CSPlayerEvent.Pause, PlayerEventSource.Sync)
                "TOGGLE" -> current.player.handleEvent(
                    CSPlayerEvent.PlayPauseToggle,
                    PlayerEventSource.Sync,
                )
                "SEEK_TO" -> current.player.seekTime(positionMs ?: return@post, PlayerEventSource.Sync)
                "SEEK_REL" -> current.player.seekTime(
                    (current.player.getPosition() ?: 0L) + (positionMs ?: 0L),
                    PlayerEventSource.Sync,
                )
                "NEXT" -> current.nextEpisode()
                "PREV" -> current.prevEpisode()
                "STOP" -> {
                    // Remote STOP/replacement is already handled by the TV controller; do not
                    // reinterpret the resulting player release as a local playback change.
                    expectedRemoteRelease = WeakReference(current)
                    current.releasePlayer()
                }
            }
        }
        return true
    }
}
