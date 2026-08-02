package com.lagradost.cloudstream3.remote.server

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.CloudStreamApp.Companion.context as appContext
import com.lagradost.cloudstream3.remote.LanRemoteProtocol
import com.lagradost.cloudstream3.remote.NowPlayingPayload
import com.lagradost.cloudstream3.remote.PlayPayload
import com.lagradost.cloudstream3.remote.PlayerCmdPayload
import com.lagradost.cloudstream3.remote.RemoteEvent
import com.lagradost.cloudstream3.ui.player.CSPlayerEvent
import com.lagradost.cloudstream3.ui.player.IPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.DataOutputStream
import java.net.Socket

/**
 * TV-side hub (plan §6.3): registry of SUBSCRIBE'd phone sockets, the active player,
 * and event broadcasting. [PlaybackReporter] is the thin facade GeneratorPlayer talks to;
 * the server talks to this hub directly for PLAY stash + PLAYER_CMD routing.
 */
object NowPlayingHub {
    private const val STATE_THROTTLE_MS = 10_000L

    private val lock = Any()
    private val subscribers = linkedMapOf<String, Socket>() // deviceId -> event socket (one per phone)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var player: IPlayer? = null
    private var currentPlay: PlayPayload? = null
    private var lastState: NowPlayingPayload? = null
    private var lastBroadcastMs = 0L
    private var lastStateKind = NowPlayingPayload.State.IDLE

    // ------------------------------------------------------------------
    // Subscribers
    // ------------------------------------------------------------------

    fun registerSubscriber(deviceId: String, socket: Socket) {
        synchronized(lock) {
            val previous = subscribers.put(deviceId, socket)
            if (previous != null && previous !== socket) {
                // The old event socket is replaced on re-subscribe; close it so its read
                // loop (and 30 s timeout) does not linger (review R7).
                runCatching { previous.close() }
            }
            // Re-send the current state so a fresh subscription gets up to speed.
            lastState?.let { state ->
                scope.launch { writeEvent(socket, RemoteEvent(kind = RemoteEvent.Kind.PLAYBACK_STATE, nowPlaying = state)) }
            }
        }
    }

    fun unregisterSubscriber(deviceId: String, socket: Socket) {
        synchronized(lock) {
            if (subscribers[deviceId] === socket) subscribers.remove(deviceId)
        }
    }

    // ------------------------------------------------------------------
    // Player
    // ------------------------------------------------------------------

    /** Stash of the last PlayPayload so state events carry title/poster before the player emits progress. */
    fun stashPlay(payload: PlayPayload) {
        synchronized(lock) { currentPlay = payload }
    }

    fun registerPlayer(player: IPlayer) {
        synchronized(lock) {
            this.player = player
            lastStateKind = NowPlayingPayload.State.IDLE
            lastBroadcastMs = 0
        }
        player.getDuration()?.let { duration ->
            player.getPosition()?.let { position ->
                reportState(position, duration, playingState(player))
            }
        }
    }

    fun unregisterPlayer() {
        val hadPlayer = synchronized(lock) {
            val had = player != null
            player = null
            currentPlay = null
            lastState = null
            lastStateKind = NowPlayingPayload.State.IDLE
            had
        }
        // Idempotent: exitPlayer() and onDestroy() both call this (review runtime #7);
        // only the first call broadcasts PLAYER_GONE.
        if (hadPlayer) broadcast(RemoteEvent(kind = RemoteEvent.Kind.PLAYER_GONE))
    }

    /** Throttled progress reporting; state transitions flush immediately (plan §6.3). */
    fun reportState(position: Long, duration: Long, state: NowPlayingPayload.State) {
        val now = System.currentTimeMillis()
        val (changed, registered) = synchronized(lock) {
            val isSame = player != null
            val changedState = state != lastStateKind
            lastStateKind = state
            changedState to isSame
        }
        if (!registered) return
        if (!changed && now - lastBroadcastMs < STATE_THROTTLE_MS) return

        val snapshot = synchronized(lock) {
            val play = currentPlay
            val speed = runCatching { player?.getPlaybackSpeed() ?: 1f }.getOrDefault(1f)
            NowPlayingPayload(
                mediaId = play?.mediaId,
                title = play?.title,
                episodeName = play?.title,
                poster = play?.poster,
                positionMs = position,
                durationMs = duration,
                state = state,
                speed = speed,
            ).also { lastState = it; lastBroadcastMs = now }
        }
        broadcast(RemoteEvent(kind = RemoteEvent.Kind.PLAYBACK_STATE, nowPlaying = snapshot))
    }

    fun currentState(): NowPlayingPayload? = synchronized(lock) { lastState }

    /** Provider apiName of the currently playing link (used to guard extension sync, plan §12). */
    fun currentPlaySource(): String? =
        synchronized(lock) { currentPlay?.links?.firstOrNull()?.source }

    fun playingState(player: IPlayer): NowPlayingPayload.State =
        runCatching { if (player.getIsPlaying()) NowPlayingPayload.State.PLAYING else NowPlayingPayload.State.PAUSED }
            .getOrDefault(NowPlayingPayload.State.PAUSED)

    // ------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------

    fun routeCommand(cmd: PlayerCmdPayload) {
        val target = synchronized(lock) { player }
        when (cmd.action) {
            PlayerCmdPayload.Action.PAUSE -> target?.handleEvent(CSPlayerEvent.Pause)
            PlayerCmdPayload.Action.RESUME -> target?.handleEvent(CSPlayerEvent.Play)
            PlayerCmdPayload.Action.PLAY_PAUSE -> target?.handleEvent(CSPlayerEvent.PlayPauseToggle)
            PlayerCmdPayload.Action.SEEK_TO -> cmd.positionMs?.let { target?.seekTo(it) }
            PlayerCmdPayload.Action.SEEK_BY -> cmd.deltaMs?.let { delta ->
                target?.getPosition()?.let { target.seekTo((it + delta).coerceAtLeast(0L)) }
            }
            PlayerCmdPayload.Action.SET_SPEED -> cmd.speed?.let { target?.setPlaybackSpeed(it) }
            PlayerCmdPayload.Action.STOP -> mainHandler.post {
                val activity = CommonActivity.activity
                if (activity is androidx.activity.ComponentActivity) {
                    activity.onBackPressedDispatcher.onBackPressed()
                } else {
                    @Suppress("DEPRECATION")
                    activity?.onBackPressed()
                }
            }
            PlayerCmdPayload.Action.VOLUME_UP -> adjustVolume(AudioManager.ADJUST_RAISE)
            PlayerCmdPayload.Action.VOLUME_DOWN -> adjustVolume(AudioManager.ADJUST_LOWER)
            PlayerCmdPayload.Action.MUTE -> adjustVolume(AudioManager.ADJUST_TOGGLE_MUTE)
        }
    }

    private fun adjustVolume(direction: Int) {
        val ctx = appContext ?: return
        val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            direction,
            AudioManager.FLAG_SHOW_UI,
        )
    }

    // ------------------------------------------------------------------
    // Broadcasting
    // ------------------------------------------------------------------

    fun broadcast(event: RemoteEvent) {
        val targets = synchronized(lock) { subscribers.values.toList() }
        targets.forEach { socket ->
            scope.launch {
                runCatching { writeEvent(socket, event) }
                    .onFailure {
                        runCatching { socket.close() }
                        synchronized(lock) {
                            subscribers.entries.removeAll { it.value === socket }
                        }
                    }
            }
        }
    }

    private fun writeEvent(socket: Socket, event: RemoteEvent) {
        // One frame per socket at a time: keep frames atomic.
        synchronized(socket) {
            LanRemoteProtocol.write(DataOutputStream(socket.getOutputStream()), event)
        }
    }
}
