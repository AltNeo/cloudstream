package com.lagradost.cloudstream3.remote.server

import com.lagradost.cloudstream3.remote.NowPlayingPayload
import com.lagradost.cloudstream3.remote.PlayPayload
import com.lagradost.cloudstream3.remote.tracksPayloadFrom
import com.lagradost.cloudstream3.ui.player.CurrentTracks
import com.lagradost.cloudstream3.ui.player.IPlayer

/**
 * Thin facade the player UI (GeneratorPlayer) talks to, so the player code never touches
 * sockets or subscribers (plan §6.3 / §11 "server/PlaybackReporter.kt").
 */
object PlaybackReporter {
    fun registerPlayer(player: IPlayer) = NowPlayingHub.registerPlayer(player)

    fun unregisterPlayer(player: IPlayer? = null) = NowPlayingHub.unregisterPlayer(player)

    /** Progress tick from the player; the hub throttles. */
    fun reportState(position: Long, duration: Long, state: NowPlayingPayload.State) =
        NowPlayingHub.reportState(position, duration, state)

    fun playingState(player: IPlayer): NowPlayingPayload.State =
        NowPlayingHub.playingState(player)

    /** F4a: push the active player's renderer tracks to opted-in phones. */
    fun reportTracks(tracks: CurrentTracks) = NowPlayingHub.broadcastTracks(tracksPayloadFrom(tracks))

    fun reportMetadata(title: String?, streamName: String?) =
        NowPlayingHub.reportMetadata(title, streamName)

    fun reportPlaybackChoices(payload: PlayPayload) = NowPlayingHub.reportPlaybackChoices(payload)

    /** Called by the server PLAY handler before the player is created. */
    fun stashPlay(payload: PlayPayload) = NowPlayingHub.stashPlay(payload)

    fun currentState(): NowPlayingPayload? = NowPlayingHub.currentState()
}
