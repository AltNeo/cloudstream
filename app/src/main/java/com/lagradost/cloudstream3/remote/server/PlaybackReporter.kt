package com.lagradost.cloudstream3.remote.server

import com.lagradost.cloudstream3.remote.NowPlayingPayload
import com.lagradost.cloudstream3.remote.PlayPayload
import com.lagradost.cloudstream3.ui.player.IPlayer

/**
 * Thin facade the player UI (GeneratorPlayer) talks to, so the player code never touches
 * sockets or subscribers (plan §6.3 / §11 "server/PlaybackReporter.kt").
 */
object PlaybackReporter {
    fun registerPlayer(player: IPlayer) = NowPlayingHub.registerPlayer(player)

    fun unregisterPlayer() = NowPlayingHub.unregisterPlayer()

    /** Progress tick from the player; the hub throttles. */
    fun reportState(position: Long, duration: Long, state: NowPlayingPayload.State) =
        NowPlayingHub.reportState(position, duration, state)

    fun playingState(player: IPlayer): NowPlayingPayload.State =
        NowPlayingHub.playingState(player)

    /** Called by the server PLAY handler before the player is created. */
    fun stashPlay(payload: PlayPayload) = NowPlayingHub.stashPlay(payload)

    fun currentState(): NowPlayingPayload? = NowPlayingHub.currentState()
}
